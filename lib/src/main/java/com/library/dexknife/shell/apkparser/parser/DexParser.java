package com.library.dexknife.shell.apkparser.parser;


import com.library.dexknife.shell.apkparser.exception.ParserException;
import com.library.dexknife.shell.apkparser.struct.dex.DexClassStruct;
import com.library.dexknife.shell.apkparser.struct.dex.DexHeader;
import com.library.dexknife.shell.apkparser.utils.Buffers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * parse dex file.
 * current we only get the class name.
 * see:
 * http://source.android.com/devices/tech/dalvik/dex-format.html
 * http://dexandroid.googlecode.com/svn/trunk/dalvik/libdex/DexFile.h
 *
 * @author dongliu
 */
public class DexParser {

    private ByteBuffer buffer;
    private ByteOrder byteOrder = ByteOrder.LITTLE_ENDIAN;

    private static final int NO_INDEX = 0xffffffff;

    private com.library.dexknife.shell.apkparser.bean.DexClass[] dexClasses;

    public DexParser(ByteBuffer buffer) {
        this.buffer = buffer.duplicate();
        this.buffer.order(byteOrder);
    }

    public void parse() {
        // read magic
        String magic = new String(Buffers.readBytes(buffer, 8));
        if (!magic.startsWith("dex\n")) {
            return;
        }
        int version = Integer.parseInt(magic.substring(4, 7));
        // now the version is 035
        if (version < 35) {
            // version 009 was used for the M3 releases of the Android platform (November�CDecember 2007),
            // and version 013 was used for the M5 releases of the Android platform (February�CMarch 2008)
            throw new ParserException("Dex file version: " + version + " is not supported");
        }

        // read header
        DexHeader header = readDexHeader();
        header.setVersion(version);

        // read string pool
        long[] stringOffsets = readStringPool(header.getStringIdsOff(), header.getStringIdsSize());

        // read types
        int[] typeIds = readTypes(header.getTypeIdsOff(), header.getTypeIdsSize());

        // read classes
        DexClassStruct[] dexClassStructs = readClass(header.getClassDefsOff(),
                header.getClassDefsSize());

        com.library.dexknife.shell.apkparser.struct.StringPool stringpool = readStrings(stringOffsets);

        String[] types = new String[typeIds.length];
        for (int i = 0; i < typeIds.length; i++) {
            types[i] = stringpool.get(typeIds[i]);
        }

        dexClasses = new com.library.dexknife.shell.apkparser.bean.DexClass[dexClassStructs.length];
        for (int i = 0; i < dexClasses.length; i++) {
            dexClasses[i] = new com.library.dexknife.shell.apkparser.bean.DexClass();
        }
        for (int i = 0; i < dexClassStructs.length; i++) {
            DexClassStruct dexClassStruct = dexClassStructs[i];
            com.library.dexknife.shell.apkparser.bean.DexClass dexClass = dexClasses[i];
            dexClass.setClassType(types[dexClassStruct.getClassIdx()]);
            if (dexClassStruct.getSuperclassIdx() != NO_INDEX) {
                dexClass.setSuperClass(types[dexClassStruct.getSuperclassIdx()]);
            }
            dexClass.setAccessFlags(dexClassStruct.getAccessFlags());
        }
    }

    /**
     * read class info.
     */
    private DexClassStruct[] readClass(long classDefsOff, int classDefsSize) {
        buffer.position((int) classDefsOff);

        DexClassStruct[] dexClassStructs = new DexClassStruct[classDefsSize];
        for (int i = 0; i < classDefsSize; i++) {
            DexClassStruct dexClassStruct = new DexClassStruct();
            dexClassStruct.setClassIdx(buffer.getInt());

            dexClassStruct.setAccessFlags(buffer.getInt());
            dexClassStruct.setSuperclassIdx(buffer.getInt());

            dexClassStruct.setInterfacesOff(Buffers.readUInt(buffer));
            dexClassStruct.setSourceFileIdx(buffer.getInt());
            dexClassStruct.setAnnotationsOff(Buffers.readUInt(buffer));
            dexClassStruct.setClassDataOff(Buffers.readUInt(buffer));
            dexClassStruct.setStaticValuesOff(Buffers.readUInt(buffer));
            dexClassStructs[i] = dexClassStruct;
        }

        return dexClassStructs;
    }

    /**
     * read types.
     */
    private int[] readTypes(long typeIdsOff, int typeIdsSize) {
        buffer.position((int) typeIdsOff);
        int[] typeIds = new int[typeIdsSize];
        for (int i = 0; i < typeIdsSize; i++) {
            typeIds[i] = (int) Buffers.readUInt(buffer);
        }
        return typeIds;
    }

    private com.library.dexknife.shell.apkparser.struct.StringPool readStrings(long[] offsets) {
        // read strings.
        // buffer some apk, the strings' offsets may not well ordered. we sort it first

        StringPoolEntry[] entries = new StringPoolEntry[offsets.length];
        for (int i = 0; i < offsets.length; i++) {
            entries[i] = new StringPoolEntry(i, offsets[i]);
        }

        String lastStr = null;
        long lastOffset = -1;
        com.library.dexknife.shell.apkparser.struct.StringPool stringpool = new com.library.dexknife.shell.apkparser.struct.StringPool(offsets.length);
        for (StringPoolEntry entry : entries) {
            if (entry.getOffset() == lastOffset) {
                stringpool.set(entry.getIdx(), lastStr);
                continue;
            }
            buffer.position((int) entry.getOffset());
            lastOffset = entry.getOffset();
            String str = readString();
            lastStr = str;
            stringpool.set(entry.getIdx(), str);
        }
        return stringpool;
    }

    /*
     * read string identifiers list.
     */
    private long[] readStringPool(long stringIdsOff, int stringIdsSize) {
        buffer.position((int) stringIdsOff);
        long offsets[] = new long[stringIdsSize];
        for (int i = 0; i < stringIdsSize; i++) {
            offsets[i] = Buffers.readUInt(buffer);
        }

        return offsets;
    }

    /**
     * read dex encoding string.
     */
    private String readString() {
        // the length is char len, not byte len
        int strLen = readVarInts();
        return Buffers.readString(buffer, strLen);
    }

    /**
     * read Modified UTF-8 encoding str.
     *
     * @param strLen the java-utf16-char len, not strLen nor bytes len.
     */
    @Deprecated
    private String readString(int strLen) {
        char[] chars = new char[strLen];

        for (int i = 0; i < strLen; i++) {
            short a = Buffers.readUByte(buffer);
            if ((a & 0x80) == 0) {
                // ascii char
                chars[i] = (char) a;
            } else if ((a & 0xe0) == 0xc0) {
                // read one more
                short b = Buffers.readUByte(buffer);
                chars[i] = (char) (((a & 0x1F) << 6) | (b & 0x3F));
            } else if ((a & 0xf0) == 0xe0) {
                short b = Buffers.readUByte(buffer);
                short c = Buffers.readUByte(buffer);
                chars[i] = (char) (((a & 0x0F) << 12) | ((b & 0x3F) << 6) | (c & 0x3F));
            } else if ((a & 0xf0) == 0xf0) {
                //throw new UTFDataFormatException();

            } else {
                //throw new UTFDataFormatException();
            }
            if (chars[i] == 0) {
                // the end of string.
            }
        }

        return new String(chars);
    }


    /**
     * read varints.
     *
     * @return
     * @throws IOException
     */
    private int readVarInts() {
        int i = 0;
        int count = 0;
        short s;
        do {
            if (count > 4) {
                throw new ParserException("read varints error.");
            }
            s = Buffers.readUByte(buffer);
            i |= (s & 0x7f) << (count * 7);
            count++;
        } while ((s & 0x80) != 0);

        return i;
    }

    private DexHeader readDexHeader() {

        // check sum. skip
        buffer.getInt();

        // signature skip
        Buffers.readBytes(buffer, DexHeader.kSHA1DigestLen);

        DexHeader header = new DexHeader();
        header.setFileSize(Buffers.readUInt(buffer));
        header.setHeaderSize(Buffers.readUInt(buffer));

        // skip?
        Buffers.readUInt(buffer);

        // static link data
        header.setLinkSize(Buffers.readUInt(buffer));
        header.setLinkOff(Buffers.readUInt(buffer));

        // the map data is just the same as dex header.
        header.setMapOff(Buffers.readUInt(buffer));

        header.setStringIdsSize(buffer.getInt());
        header.setStringIdsOff(Buffers.readUInt(buffer));

        header.setTypeIdsSize(buffer.getInt());
        header.setTypeIdsOff(Buffers.readUInt(buffer));

        header.setProtoIdsSize(buffer.getInt());
        header.setProtoIdsOff(Buffers.readUInt(buffer));

        header.setFieldIdsSize(buffer.getInt());
        header.setFieldIdsOff(Buffers.readUInt(buffer));

        header.setMethodIdsSize(buffer.getInt());
        header.setMethodIdsOff(Buffers.readUInt(buffer));

        header.setClassDefsSize(buffer.getInt());
        header.setClassDefsOff(Buffers.readUInt(buffer));

        header.setDataSize(buffer.getInt());
        header.setDataOff(Buffers.readUInt(buffer));

        buffer.position((int) header.getHeaderSize());

        return header;
    }

    public com.library.dexknife.shell.apkparser.bean.DexClass[] getDexClasses() {
        return dexClasses;
    }

}

