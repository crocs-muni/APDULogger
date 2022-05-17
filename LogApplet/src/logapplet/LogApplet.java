package logapplet;

import javacard.framework.*;
import javacardx.apdu.ExtendedLength;

/**
 * The structure of this applet (constructor, install, select,
 * deselect and basis of process method) was adapted from Petr Svenda.
 * 
 * Author of other methods is Lubomir Hrbacek
 * @author Lubomir Hrbacek
*/

public class LogApplet extends javacard.framework.Applet implements ExtendedLength {

    // MAIN INSTRUCTION CLASS
    final static byte CLA_LOGAPPLET = (byte) 0xB4;
    
    // APPLET INSTRUCTIONS
    final static byte INS_SEND_LOG_LEN  = (byte) 0x50;
    final static byte INS_SEND_LOG      = (byte) 0x51;
    final static byte INS_MODIFY_LOG    = (byte) 0x52;
    
    // OTHER APPLET CONSTANTS
    final static short EEPROM_SIZE       = (short) 10000;
    final static byte APDU_T             = (byte) 0;
    final static byte RSPS_T             = (byte) 1;
    final static byte LOG_OFFSET_ITER    = (byte) 0;
    final static byte LOG_OFFSET_TYPE    = (byte) 1;
    final static byte LOG_OFFSET_LEN     = (byte) 2;
    final static byte LOG_OFFSET_DATA    = (byte) 4;
    final static byte APDU_HEAD_LEN      = (byte) 4;
    final static byte APDU_HEAD_LEN_EXT  = (byte) 7;
    final static byte APDU_LC_1_EXT      = (byte) 5;
    final static byte APDU_LC_2_EXT      = (byte) 6;

    final static short SW_Exception                         = (short) 0xff01;
    final static short SW_ArrayIndexOutOfBoundsException    = (short) 0xff02;
    final static short SW_ArithmeticException               = (short) 0xff03;
    final static short SW_ArrayStoreException               = (short) 0xff04;
    final static short SW_NullPointerException              = (short) 0xff05;
    final static short SW_NegativeArraySizeException        = (short) 0xff06;
    final static short SW_CryptoException_prefix            = (short) 0xf100;
    final static short SW_SystemException_prefix            = (short) 0xf200;
    final static short SW_PINException_prefix               = (short) 0xf300;
    final static short SW_TransactionException_prefix       = (short) 0xf400;
    final static short SW_CardRuntimeException_prefix       = (short) 0xf500;
    
    
    // PERSISTENT ARRAY IN EEPROM
    private byte m_dataArray[] = null;
    // PERSISTENT VARIABLE FOR ITERATION COUNT
    private byte iterCount = (byte) 0;

    /**
     * LogApplet default constructor. Only this class's install method should
     * create the applet object.
     * 
     * @param buffer received buffer
     * @param offset offset of the buffer
     * @param length length of the buffer
     */
    protected LogApplet(byte[] buffer, short offset, byte length) {
        // Data offset is used for application specific parameter
        // Initialization with default offset (AID offset)
        short dataOffset = offset;
        boolean isOP2 = false;

        if (length > (byte) 9) {
            // Install parameter detail. Compliant with OP 2.0.1.

            // | size | content
            // |------|---------------------------
            // |  1   | [AID_Length]
            // | 5-16 | [AID_Bytes]
            // |  1   | [Privilege_Length]
            // | 1-n  | [Privilege_Bytes] (normally 1Byte)
            // |  1   | [Application_Proprietary_Length]
            // | 0-m  | [Application_Proprietary_Bytes]
            
            // Shift to privilege offset
            dataOffset += (short) (1 + buffer[offset]);
            // Finally shift to Application specific offset
            dataOffset += (short) (1 + buffer[dataOffset]);
            // Go to proprietary data
            dataOffset++;

            // INITIALISATION OF EEPROM
            m_dataArray = new byte[EEPROM_SIZE];
            Util.arrayFillNonAtomic(m_dataArray, (short) 0, EEPROM_SIZE, (byte) 0);

            // Update flag
            isOP2 = true;
        } 
        // Register this instance
        register();
    }

    /**
     * Method installing the applet.
     *
     * @param bArray the array containing installation parameters
     * @param bOffset the starting offset in bArray
     * @param bLength the length in bytes of the data parameter in bArray
     */
    public static void install(byte[] bArray, short bOffset, byte bLength) throws ISOException {
        // Applet  instance creation 
        new LogApplet(bArray, bOffset, bLength);
    }

    /**
     * Select method returns true if applet selection is supported.
     *
     * @return boolean status of selection.
     */
    public boolean select() {
        clearSessionData();
        return true;
    }

    /**
     * Deselect method called by the system in the deselection process.
     */
    public void deselect() {
        clearSessionData();
    }

    /**
     * Method processing an incoming APDU.
     *
     * @see APDU
     * @param apdu the incoming APDU
     * @exception ISOException with the response bytes defined by ISO 7816-4
     */
    public void process(APDU apdu) throws ISOException {
        // Get the buffer with incoming APDU
        byte[] apduBuffer = apdu.getBuffer();

        try {
            if (apduBuffer[ISO7816.OFFSET_CLA] == CLA_LOGAPPLET) {
                parseServiceAPDU(apdu);
            } else {
                parseUnknownAPDU(apdu);
            }
        // Capture all reasonable exceptions and change into readable ones (instead of 0x6f00) 
        } catch (ISOException e) {
            throw e; // Our exception from code, just re-emit
        } catch (ArrayIndexOutOfBoundsException e) {
            ISOException.throwIt(SW_ArrayIndexOutOfBoundsException);
        } catch (ArithmeticException e) {
            ISOException.throwIt(SW_ArithmeticException);
        } catch (ArrayStoreException e) {
            ISOException.throwIt(SW_ArrayStoreException);
        } catch (NullPointerException e) {
            ISOException.throwIt(SW_NullPointerException);
        } catch (NegativeArraySizeException e) {
            ISOException.throwIt(SW_NegativeArraySizeException);
        } catch (SystemException e) {
            ISOException.throwIt((short) (SW_SystemException_prefix | e.getReason()));
        } catch (TransactionException e) {
            ISOException.throwIt((short) (SW_TransactionException_prefix | e.getReason()));
        } catch (CardRuntimeException e) {
            ISOException.throwIt((short) (SW_CardRuntimeException_prefix | e.getReason()));
        } catch (Exception e) {
            ISOException.throwIt(SW_Exception);
        }
    }

    /**
     * Method reseting iteration count variable in EEPROM when communication start or end.
     */
    void clearSessionData() {
        iterCount = (short) 0;
    }

    /**
     * Method finding record in log according to iteration number.
     *
     * @param iter iteration number
     * @return offset in log, where is record of the APDU specified by iteration
     */
    short getLogOffset(byte iter) {
        short offset = (short) 0;
        for (short i = (short) 0; i < (short) ((short) 2 * iter); i++) {
            if (m_dataArray[(short) (offset + LOG_OFFSET_ITER)] != (short) (i / (short) 2)
                || Util.getShort(m_dataArray, (short) (offset + LOG_OFFSET_LEN)) == 0) {
                    // Offset is not actual iteration in for loop or iteration is correct, but no data
                    ISOException.throwIt(SW_Exception); // Inconsistence in log
            }
            offset += LOG_OFFSET_DATA + Util.getShort(m_dataArray, (short) (offset + LOG_OFFSET_LEN));
        }
        return offset;
    }
    
    /**
     * Method checking if recieved Command APDU is whole in the log alongside with its Response APDU
     * 
     * @param apdu received Command APDU
     * @return 0 if incoming Command APDu is in log in correct position and with Response APDU, 1 if it is new apdu
     */
    byte checkLog(APDU apdu) {
        byte[] apduBuffer = apdu.getBuffer();
        short offset = getLogOffset(iterCount);
        short loggedApduLen = Util.getShort(m_dataArray, (short) (offset + LOG_OFFSET_LEN));
        
        if (0 == loggedApduLen) {
            return (byte) 1;
        }
        if (0 != Util.arrayCompare(m_dataArray, (short) (offset + LOG_OFFSET_DATA),
                        apduBuffer, (short) 0, loggedApduLen)) {
            // Received APDU does not match logged APDU 
            ISOException.throwIt(SW_Exception);
        }
        if (0 == Util.getShort(m_dataArray,
                        (short) (offset + LOG_OFFSET_DATA + loggedApduLen + LOG_OFFSET_LEN))) {
            // No logged Response APDU
            ISOException.throwIt(SW_Exception);
        }
        return (byte) 0;
    }
    
    /**
     * Method sending the Response APDU.
     *
     * @param apdu received Command APDU
     */
    void sendResponse(APDU apdu) {
        short offset = getLogOffset(iterCount);
        iterCount++;
        // Get offset of respective place in log of the Response APDU
        offset += LOG_OFFSET_DATA + Util.getShort(m_dataArray, (short) (offset + LOG_OFFSET_LEN));
        // Response APDU length is calculated with two bytes SW1 and SW2
        short responseLen = Util.getShort(m_dataArray, (short) (offset + LOG_OFFSET_LEN));
        if (responseLen == 2) {
            // No data, only SW1 and SW2
            if (Util.getShort(m_dataArray, (short) (offset + LOG_OFFSET_DATA)) == (short) 0x9000) {
                // Send no data and no error - 0x9000
                apdu.setOutgoingAndSend(ISO7816.OFFSET_CDATA, (byte) 0);
            } else {
                // Throw exception with error code == send SW of that error
                ISOException.throwIt(Util.getShort(m_dataArray, (short) (offset + LOG_OFFSET_DATA)));
            }
            return;
        }
        byte[] apduBuffer = apdu.getBuffer();
        if (((short) (responseLen - 2)) <= (byte) 0xff) {
            Util.arrayCopyNonAtomic(m_dataArray, (short) (offset + LOG_OFFSET_DATA),
                    apduBuffer, ISO7816.OFFSET_CDATA, (short) (responseLen - 2));
            // Assuming SW is 0x9000 when responding with data
            apdu.setOutgoingAndSend(ISO7816.OFFSET_CDATA, (short) (responseLen - 2));
        } else {
            // Extended Response APDU
            apdu.setOutgoing();
            apdu.setOutgoingLength((short) (responseLen - 2));
            apdu.sendBytesLong(m_dataArray, (short) (offset + LOG_OFFSET_DATA),
                    (short) (responseLen - (byte) 2));
        }
    }  
    
    /**
     * Method logging the new received Command APDU.
     * It does not differ between Case 1 and Case 2 APDUs and between Case 3 and Case 4 APDUs.
     * -- when Le is not present or zero, it prepends zero Le in log if possible
     *
     * @param apdu received Command APDU
     * @param iter iteration number
     */
    void logNewAPDU(APDU apdu, byte iter) {
        byte[] apduBuffer = apdu.getBuffer();
        short offset = getLogOffset(iter);
        
        m_dataArray[(short) (offset + LOG_OFFSET_ITER)] = iter;
        m_dataArray[(short) (offset + LOG_OFFSET_TYPE)] = APDU_T;
        
        if(apduBuffer[ISO7816.OFFSET_LC] == 0) {
            if (apduBuffer[APDU_LC_1_EXT] != 0 || apduBuffer[APDU_LC_2_EXT] != 0) {
                // Extended APDU
                short dataLen = Util.getShort(apduBuffer, APDU_LC_1_EXT);
                Util.setShort(m_dataArray, (short) (offset + LOG_OFFSET_LEN),
                        (short) (dataLen + APDU_HEAD_LEN_EXT));
                // Log the head of APDU
                Util.arrayCopyNonAtomic(apduBuffer, (short) 0,
                        m_dataArray, (short) (offset + LOG_OFFSET_DATA), APDU_HEAD_LEN_EXT);
                
                dataLen = apdu.setIncomingAndReceive();
                short pointer = (short) 0;
                short offsetData = APDU_HEAD_LEN_EXT;
                while (dataLen > (short) 0) 
                {
                    Util.arrayCopyNonAtomic(apduBuffer, offsetData,
                            m_dataArray, (short) (offset + LOG_OFFSET_DATA + APDU_HEAD_LEN_EXT + pointer),
                            dataLen);
                    pointer += dataLen;
                    // Gets as many data bytes as will fit without APDU buffer overflow
                    dataLen = apdu.receiveBytes(offsetData);
                }
            } else {
                // Classic Case 2 APDU
                // Classic APDU header and Le zero byte is 5 bytes
                Util.setShort(m_dataArray, (short) (offset + LOG_OFFSET_LEN),
                        (short) (APDU_HEAD_LEN + 1));
                Util.arrayCopyNonAtomic(apduBuffer, (short) 0, m_dataArray,
                        (short) (offset + LOG_OFFSET_DATA), (short) (APDU_HEAD_LEN + 1));
            }
        } else {
            boolean allZeros = true;
            for (short i = (short) ISO7816.OFFSET_CDATA; i < apduBuffer.length; i++) {
                if (apduBuffer[i] != 0) {
                    allZeros = false;
                }
            }
            if (allZeros) {
                // apduBuffer is header, non-zero byte and zeros
                // Logging as Classic Case 2 APDU - fifth byte is Le
                Util.setShort(m_dataArray, (short) (offset + LOG_OFFSET_LEN),
                        (short) (APDU_HEAD_LEN + 1));
                Util.arrayCopyNonAtomic(apduBuffer, (short) 0, m_dataArray,
                        (short) (offset + LOG_OFFSET_DATA), (short) (APDU_HEAD_LEN + 1));
            } else {
                short apduLen;
                if (apduBuffer[ISO7816.OFFSET_LC] == (byte) 0xff) {
                    // Length of data is 255 bytes, logging only header, Lc and data
                    apduLen = (short) 260; 
                } else {
                    // Length of data is smaller than 255 bytes
                    // Logging header, Lc , data and Le as zero byte
                    apduLen = (short) (apduBuffer[ISO7816.OFFSET_LC] + APDU_HEAD_LEN + 2);
                }
                Util.setShort(m_dataArray, (short) (offset + LOG_OFFSET_LEN), apduLen);
                Util.arrayCopyNonAtomic(apduBuffer, (short) 0,
                            m_dataArray, (short) (offset + LOG_OFFSET_DATA), apduLen); 
            }
        }
    }
    
    /**
     * Method sending the log length.
     *
     * @param apdu received Command APDU
     */
    void serviceSendLogLen(APDU apdu) {
        byte[] apduBuffer = apdu.getBuffer();
        Util.setShort(apduBuffer, ISO7816.OFFSET_CDATA, EEPROM_SIZE);
        apdu.setOutgoingAndSend(ISO7816.OFFSET_CDATA, (short) 2);
    }
    
    /**
     * Method sending part of the log as Response APDU according to the offset.
     *
     * @param apdu received Command APDU
     * @param logOffset offset of the log
     */
    void serviceSendLog(APDU apdu, short logOffset) {
        byte[] apduBuffer = apdu.getBuffer();
        short dataLen = (short) (EEPROM_SIZE - logOffset) < (short) 255
                ? (short) (EEPROM_SIZE - logOffset)
                : (short) 255;

        Util.arrayCopyNonAtomic(m_dataArray, logOffset,
                apduBuffer, (short) 0, dataLen);
        apdu.setOutgoingAndSend((short) 0, dataLen);
    }
    
    /**
     * Method modifying the log according to the APDU from ControlService.
     *
     * @param apdu received Command APDU
     * @param logOffset offset of the log where the change should happen
     */
    void serviceModifyLog(APDU apdu, short logOffset) {
        byte[] apduBuffer = apdu.getBuffer();
        short dataLen = apdu.setIncomingAndReceive();
        Util.arrayCopyNonAtomic(apduBuffer, ISO7816.OFFSET_CDATA,
                m_dataArray, logOffset, dataLen);
    }
    
    /**
     * Method parsing the APDU from ControlService.
     *
     * @param apdu received Command APDU
     */
    void parseServiceAPDU(APDU apdu) {
        byte[] apduBuffer = apdu.getBuffer();        
        short logOffset = Util.getShort(apduBuffer, ISO7816.OFFSET_P1);
        if (logOffset >= EEPROM_SIZE) {
            ISOException.throwIt(SW_ArrayIndexOutOfBoundsException);
        }
        
        switch (apduBuffer[ISO7816.OFFSET_INS]) {
            case INS_SEND_LOG_LEN:
                serviceSendLogLen(apdu);
                break;
            case INS_SEND_LOG:
                serviceSendLog(apdu, logOffset);
                break;
            case INS_MODIFY_LOG:
                serviceModifyLog(apdu, logOffset);
                break;
            default:
                // The INS code is not supported by the dispatcher
                ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
                break;
        }
    }
    
    /**
     * Method deciding what to do with received unknown APDU.
     *
     * @param apdu received Command APDU
     */
    void parseUnknownAPDU(APDU apdu) {
        if (Util.getShort(m_dataArray, LOG_OFFSET_LEN) == (byte) 0) {
            // No data in log
            if (iterCount != (byte) 0) {
                ISOException.throwIt(SW_Exception);
            }
            logNewAPDU(apdu, (byte) 0);
            ISOException.throwIt(SW_Exception); // Nothing to respond
        } else {
            if (checkLog(apdu) == (byte) 0) {
                 // APDU and response found
                sendResponse(apdu);
            } else {
                logNewAPDU(apdu, iterCount);
                ISOException.throwIt(SW_Exception); // Nothing to respond
            }
        }
    }
}
