/*
 * Copyright (C) 2018-2019 Confidential Technologies GmbH
 *
 * You can purchase a commercial license at https://hwsecurity.dev.
 * Buying such a license is mandatory as soon as you develop commercial
 * activities involving this program without disclosing the source code
 * of your own applications.
 *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.cotech.hw.fido.internal;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import de.cotech.hw.SecurityKeyException;
import de.cotech.hw.exceptions.*;
import de.cotech.hw.fido.exceptions.FidoPresenceRequiredException;
import de.cotech.hw.fido.exceptions.FidoWrongKeyHandleException;
import de.cotech.hw.internal.iso7816.CommandApdu;
import de.cotech.hw.internal.iso7816.ResponseApdu;
import de.cotech.hw.internal.transport.SecurityKeyInfo.TransportType;
import de.cotech.hw.internal.transport.Transport;
import de.cotech.hw.util.Hex;
import timber.log.Timber;


@RestrictTo(Scope.LIBRARY_GROUP)
public class FidoU2fAppletConnection {
    private static final int APDU_SW1_RESPONSE_AVAILABLE = 0x61;
    private static final int RESPONSE_SW1_INCORRECT_LENGTH = 0x6C;

    private static final List<byte[]> FIDO_AID_PREFIXES = Arrays.asList(
            // see to "FIDO U2F NFC protocol", Section 5. Applet selection
            // https://fidoalliance.org/specs/fido-u2f-v1.2-ps-20170411/fido-u2f-nfc-protocol-v1.2-ps-20170411.html
            Hex.decodeHexOrFail("A0000006472F0001"),
            // Workaround for Solokey: https://github.com/solokeys/solo/issues/213
            Hex.decodeHexOrFail("A0000006472F000100"),
            // old Yubico demo applet AID
            Hex.decodeHexOrFail("A0000005271002")
    );

    @NonNull
    private final Transport transport;
    @NonNull
    private final FidoU2fCommandApduFactory commandFactory;

    private boolean isFidoAppletConnected;

    public static FidoU2fAppletConnection getInstanceForTransport(@NonNull Transport transport) {
        return new FidoU2fAppletConnection(transport, new FidoU2fCommandApduFactory());
    }

    private FidoU2fAppletConnection(@NonNull Transport transport, @NonNull FidoU2fCommandApduFactory commandFactory) {
        this.transport = transport;
        this.commandFactory = commandFactory;
    }

    // region connection management

    public void connectIfNecessary() throws IOException {
        if (isFidoAppletConnected) {
            return;
        }

        connectToDevice();
    }

    private void connectToDevice() throws IOException {
        try {
            if (transport.getTransportType() == TransportType.USB_U2FHID) {
                Timber.d("Using USB U2F HID as a transport. No need to select AID.");
                byte[] versionBytes = readVersion();
                checkVersionOrThrow(versionBytes);
            } else {
                byte[] selectedAid = selectFilesFromPrefixOrFail();
                Timber.d("Connected to AID %s", Hex.encodeHexString(selectedAid));
            }

            isFidoAppletConnected = true;
        } catch (IOException e) {
            transport.release();
            throw e;
        }
    }

    private byte[] selectFilesFromPrefixOrFail() throws IOException {
        for (byte[] fileAid : FIDO_AID_PREFIXES) {
            byte[] initializedAid = selectFileOrFail(fileAid);
            if (initializedAid != null) {
                return initializedAid;
            }
        }
        throw new SelectAppletException(FIDO_AID_PREFIXES);
    }

    private void checkVersionOrThrow(byte[] versionBytes) throws IOException {
        String version = new String(versionBytes, Charset.forName("ASCII"));

        if ("U2F_V2".equals(version)) {
            Timber.d("U2F applet answered correctly with version U2F_V2");
        } else {
            Timber.e("U2F applet did NOT answer with a correct version string!");
            throw new IOException("Applet replied with incorrect version string!");
        }
    }

    private byte[] selectFileOrFail(byte[] fileAid) throws IOException {
        CommandApdu select = commandFactory.createSelectFileCommand(fileAid);

        try {
            ResponseApdu response = communicateOrThrow(select);

            // "FIDO authenticator SHALL reply with its version string in the successful response"
            // https://fidoalliance.org/specs/fido-u2f-v1.2-ps-20170411/fido-u2f-nfc-protocol-v1.2-ps-20170411.html
            checkVersionOrThrow(response.getData());
            return fileAid;
        } catch (AppletFileNotFoundException e) {
            return null;
        }
    }

    // endregion

    // region communication

    // ISO/IEC 7816-4
    private ResponseApdu communicate(CommandApdu commandApdu) throws IOException {
        ResponseApdu lastResponse;

        lastResponse = transceiveWithChaining(commandApdu);
        if (lastResponse.getSw1() == RESPONSE_SW1_INCORRECT_LENGTH && lastResponse.getSw2() != 0) {
            commandApdu = commandApdu.withNe(lastResponse.getSw2());
            lastResponse = transceiveWithChaining(commandApdu);
        }
        lastResponse = readChainedResponseIfAvailable(lastResponse);

        return lastResponse;
    }

    // see "FIDO U2F Raw Message Formats", Section 3.3 Status Codes
    // https://fidoalliance.org/specs/fido-u2f-v1.2-ps-20170411/fido-u2f-raw-message-formats-v1.2-ps-20170411.html
    public ResponseApdu communicateOrThrow(CommandApdu commandApdu) throws IOException {
        ResponseApdu response = communicate(commandApdu);

        if (response.isSuccess()) {
            return response;
        }

        switch (response.getSw()) {
            case FidoPresenceRequiredException.SW_TEST_OF_USER_PRESENCE_REQUIRED:
                throw new FidoPresenceRequiredException();
            case FidoWrongKeyHandleException.SW_WRONG_KEY_HANDLE:
                throw new FidoWrongKeyHandleException();
            case AppletFileNotFoundException.SW_FILE_NOT_FOUND:
                throw new AppletFileNotFoundException();
            case ClaNotSupportedException.SW_CLA_NOT_SUPPORTED:
                throw new ClaNotSupportedException();
            case InsNotSupportedException.SW_INS_NOT_SUPPORTED:
                throw new InsNotSupportedException();
            case WrongRequestLengthException.SW_WRONG_REQUEST_LENGTH:
                throw new WrongRequestLengthException();
            default:
                throw new SecurityKeyException("UNKNOWN", response.getSw());
        }
    }

    // ISO/IEC 7816-4
    @NonNull
    private ResponseApdu transceiveWithChaining(CommandApdu commandApdu) throws IOException {
        /* If extended length is supported, we use an Lc of 65536 to enforce extended length
         * APDUs for the register and authenticate commands.
         *
         * This forces APDU case 4e in CommandApdu:
         * apdu[apdu.length - 2] = 0;
         * apdu[apdu.length - 1] = 0;
         *
         * see also:
         * https://fidoalliance.org/specs/fido-u2f-v1.2-ps-20170411/fido-u2f-hid-protocol-v1.2-ps-20170411.html
         * https://docs.oracle.com/javacard/3.0.5/prognotes/extended_apdu_format.htm
         */
        if (transport.isExtendedLengthSupported() && commandFactory.isSuitableForExtendedApdu(commandApdu)) {
            CommandApdu extendedLengthApdu = commandApdu.withNe(65536);
            ResponseApdu response = transport.transceive(extendedLengthApdu);
            if (response.getSw() == WrongRequestLengthException.SW_WRONG_REQUEST_LENGTH) {
                Timber.d("Received WRONG_REQUEST_LENGTH error. Retrying with compatibility workaround");
                CommandApdu shortApdu = commandFactory.createShortApdu(commandApdu);
                return transport.transceive(shortApdu);
            }
            return response;
        }

        if (commandFactory.isSuitableForShortApdu(commandApdu)) {
            CommandApdu shortApdu = commandFactory.createShortApdu(commandApdu);
            return transport.transceive(shortApdu);
        }

        ResponseApdu lastResponse = null;

        List<CommandApdu> chainedApdus = commandFactory.createChainedApdus(commandApdu);
        for (int i = 0, totalCommands = chainedApdus.size(); i < totalCommands; i++) {
            CommandApdu chainedApdu = chainedApdus.get(i);
            lastResponse = transport.transceive(chainedApdu);

            boolean isLastCommand = (i == totalCommands - 1);
            if (!isLastCommand && !lastResponse.isSuccess()) {
                throw new IOException("Failed to chain apdu " +
                        "(" + i + "/" + (totalCommands - 1) + ", last SW: " + Integer.toHexString(lastResponse.getSw()) + ")");
            }
        }

        if (lastResponse == null) {
            throw new IllegalStateException();
        }

        return lastResponse;
    }

    // ISO/IEC 7816-4
    @NonNull
    private ResponseApdu readChainedResponseIfAvailable(ResponseApdu lastResponse) throws
            IOException {
        if (lastResponse.getSw1() != APDU_SW1_RESPONSE_AVAILABLE) {
            return lastResponse;
        }

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(lastResponse.getData());

        do {
            // GET RESPONSE ISO/IEC 7816-4 par.7.6.1
            CommandApdu getResponse = commandFactory.createGetResponseCommand(lastResponse.getSw2());
            lastResponse = transport.transceive(getResponse);
            result.write(lastResponse.getData());
        } while (lastResponse.getSw1() == APDU_SW1_RESPONSE_AVAILABLE);

        result.write(lastResponse.getSw1());
        result.write(lastResponse.getSw2());

        return ResponseApdu.fromBytes(result.toByteArray());
    }

    // endregion

    @NonNull
    public FidoU2fCommandApduFactory getCommandFactory() {
        return commandFactory;
    }

    /**
     * GetVersion Request
     * https://fidoalliance.org/specs/fido-u2f-v1.2-ps-20170411/fido-u2f-raw-message-formats-v1.2-ps-20170411.html
     * <p>
     * The FIDO Client can query the U2F token about the U2F protocol version that it implements.
     *
     * @return should return "U2F_V2"
     */
    private byte[] readVersion() throws IOException {
        CommandApdu getDataUserIdCommand = commandFactory.createVersionCommand();
        ResponseApdu responseApdu = communicateOrThrow(getDataUserIdCommand);
        return responseApdu.getData();
    }

    public boolean isConnected() {
        return transport.isConnected();
    }
}
