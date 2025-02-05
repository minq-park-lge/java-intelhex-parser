/**
 * Copyright (c) 2015, Jan Breuer All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package cz.jaybee.intelhex;

import java.io.*;

/**
 * Main Intel HEX parser class
 *
 * @author Jan Breuer
 * @author Kristian Sloth Lauszus
 * @author riilabs
 */
public class Parser {

    private final BufferedReader reader;
    private DataListener dataListener = null;
    private static final int HEX = 16;
    private boolean eof = false;
    private int recordIdx = 0;
    private long upperAddress = 0;
    private long startAddress = 0;

    /**
     * Constructor of the parser with reader
     *
     * @param reader
     */
    public Parser(Reader reader) {
        this.reader = (reader instanceof BufferedReader) ? (BufferedReader) reader : new BufferedReader(reader);
    }

    /**
     * Constructor of the parser with input stream
     *
     * @param stream
     */
    public Parser(InputStream stream) {
        this.reader = new BufferedReader(new InputStreamReader(stream));
    }

    /**
     * Set data listener to parsing events (data and eof)
     *
     * @param listener
     */
    public void setDataListener(DataListener listener) {
        this.dataListener = listener;
    }

    /**
     * Parse one line of Intel HEX file
     *
     * @param string record
     * @return parsed record
     * @throws IntelHexException
     */
    private Record parseRecord(String record) throws IntelHexException {
        Record result = new Record();
        // check, if there wasn an accidential EOF record
        if (eof) {
            throw new IntelHexException("Data after eof (" + recordIdx + ")");
        }

        // every IntelHEX record must start with ":"
        if (!record.startsWith(":")) {
            throw new IntelHexException("Invalid Intel HEX record (" + recordIdx + ")");
        }

        int lineLength = record.length();
        byte[] hexRecord = new byte[lineLength / 2];

        // sum of all bytes modulo 256 (including checksum) shuld be 0
        int sum = 0;
        for (int i = 0; i < hexRecord.length; i++) {
            String num = record.substring(2 * i + 1, 2 * i + 3);
            hexRecord[i] = (byte) Integer.parseInt(num, HEX);
            sum += hexRecord[i] & 0xff;
        }
        sum &= 0xff;

        if (sum != 0) {
            throw new IntelHexException("Invalid checksum (" + recordIdx + ")");
        }

        // if the length field does not correspond with line length
        if (hexRecord[0] < 0) // FIX #7( https://github.com/j123b567/java-intelhex-parser/issues/7) ':FF02000048474645444353525150...' -> hexRecord[0] = -1
            result.length = (int) hexRecord[0] + 256;
        else
            result.length = (int) hexRecord[0];

        if ((result.length + 5) != hexRecord.length) {
            throw new IntelHexException("Invalid record length (" + recordIdx + ")");
        }
        // length is OK, copy data
        result.data = new byte[result.length];
        System.arraycopy(hexRecord, 4, result.data, 0, result.length);

        // build lower part of data address
        result.address = ((hexRecord[1] & 0xFF) << 8) + (hexRecord[2] & 0xFF);

        // determine record type
        result.type = RecordType.fromInt(hexRecord[3] & 0xFF);
        if (result.type == RecordType.UNKNOWN) {
            throw new IntelHexException("Unsupported record type " + (hexRecord[3] & 0xFF) + " (" + recordIdx + ")");
        }

        return result;
    }

    /**
     * Process parsed record, copute correct address, emit events
     *
     * @param record
     * @throws IntelHexException
     */
    private void processRecord(Record record) throws IntelHexException {
        // build full address
        long addr = record.address | upperAddress;
        switch (record.type) {
            case DATA:
                if (dataListener != null) {
                    dataListener.data(addr, record.data);
                }
                break;
            case EOF:
                if (dataListener != null) {
                    dataListener.eof();
                }
                eof = true;
                break;
            case EXT_LIN:
                if (record.length == 2) {
                    upperAddress = ((record.data[0] & 0xFF) << 8) + (record.data[1] & 0xFF);
                    upperAddress <<= 16; // ELA is bits 16-31 of the segment base address (SBA), so shift left 16 bits
                } else {
                    throw new IntelHexException("Invalid EXT_LIN record (" + recordIdx + ")");
                }

                break;
            case EXT_SEG:
                if (record.length == 2) {
                    upperAddress = ((record.data[0] & 0xFF) << 8) + (record.data[1] & 0xFF);
                    upperAddress <<= 4; // ESA is bits 4-19 of the segment base address (SBA), so shift left 4 bits
                } else {
                    throw new IntelHexException("Invalid EXT_SEG record (" + recordIdx + ")");
                }
                break;
            case START_LIN:
                if (record.length == 4) {
                    startAddress = 0;
                    for (byte c : record.data) {
                        startAddress = startAddress << 8;
                        startAddress |= (c & 0xFF);
                    }
                } else {
                    throw new IntelHexException("Invalid START_LIN record at line #" + recordIdx + " " + record);
                }
                break;
            case START_SEG:
                if (record.length == 4) {
                    startAddress = 0;
                    for (byte c : record.data) {
                        startAddress = startAddress << 8;
                        startAddress |= (c & 0xFF);
                    }
                } else {
                    throw new IntelHexException("Invalid START_SEG record at line #" + recordIdx + " " + record);
                }
                break;
            case UNKNOWN:
                break;
        }

    }

    /**
     * Return program start address/reset address. May not be at the beggining
     * of the data.
     *
     * @return Start address
     */
    public long getStartAddress() {
        return startAddress;
    }

    /**
     * Main public method to start parsing of the input
     *
     * @throws IntelHexException
     * @throws IOException
     */
    public void parse() throws IntelHexException, IOException {
        eof = false;
        recordIdx = 1;
        upperAddress = 0;
        startAddress = 0;
        String recordStr;

        while ((recordStr = reader.readLine()) != null) {
            // Ignore if this is a blank line.
            if (recordStr.isEmpty()) {
                continue;
            }
            Record record = parseRecord(recordStr);
            processRecord(record);
            recordIdx++;
        }

        if (!eof) {
            throw new IntelHexException("No eof at the end of file");
        }
    }
}
