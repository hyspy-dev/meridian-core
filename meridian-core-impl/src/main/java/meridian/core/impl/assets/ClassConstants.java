package meridian.core.impl.assets;

import java.nio.ByteBuffer;

/**
 * Reads a {@code static final int} constant straight off a class file - without loading the class,
 * which may be built for a newer Java than this one runs on.
 */
final class ClassConstants {

    private ClassConstants() {
    }

    /** The value of the int constant {@code field}, or {@code null} when the class has none. */
    static Integer intConstant(byte[] classFile, String field) {
        ByteBuffer b = ByteBuffer.wrap(classFile);
        if (b.getInt() != 0xCAFEBABE) {
            return null;
        }
        b.getShort(); // minor
        b.getShort(); // major
        int count = Short.toUnsignedInt(b.getShort());
        String[] utf8 = new String[count];
        Integer[] ints = new Integer[count];
        for (int i = 1; i < count; i++) {
            int tag = Byte.toUnsignedInt(b.get());
            switch (tag) {
                case 1 -> {
                    byte[] s = new byte[Short.toUnsignedInt(b.getShort())];
                    b.get(s);
                    utf8[i] = new String(s, java.nio.charset.StandardCharsets.UTF_8);
                }
                case 3 -> ints[i] = b.getInt();
                case 4 -> b.getInt();
                case 5, 6 -> {
                    b.getLong();
                    i++; // longs and doubles take two slots
                }
                case 7, 8, 16, 19, 20 -> b.getShort();
                case 9, 10, 11, 12, 17, 18 -> b.getInt();
                case 15 -> {
                    b.get();
                    b.getShort();
                }
                default -> {
                    return null;
                }
            }
        }
        b.getShort(); // access
        b.getShort(); // this
        b.getShort(); // super
        int interfaces = Short.toUnsignedInt(b.getShort());
        b.position(b.position() + 2 * interfaces);
        int fields = Short.toUnsignedInt(b.getShort());
        for (int f = 0; f < fields; f++) {
            b.getShort(); // access
            String name = utf8[Short.toUnsignedInt(b.getShort())];
            b.getShort(); // descriptor
            int attributes = Short.toUnsignedInt(b.getShort());
            for (int a = 0; a < attributes; a++) {
                String attribute = utf8[Short.toUnsignedInt(b.getShort())];
                int length = b.getInt();
                if (field.equals(name) && "ConstantValue".equals(attribute)) {
                    return ints[Short.toUnsignedInt(b.getShort())];
                }
                b.position(b.position() + length);
            }
        }
        return null;
    }
}
