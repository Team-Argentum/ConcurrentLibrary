package net.sixik.concurrent_library.utils.nbt;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class FastNbtSample {

    public static void main(String[] args) throws IOException {
        System.out.println("=== 1. Writing NBT data using FastNbtWriter ===");

        FastNbtWriter writer = new FastNbtWriter();
        writer.beginRoot() // Unnamed root compound (Minecraft standard)
                .putInt("DataVersion", 3955)
                .putString("PlayerName", "Steve")
                .putShort("Health", (short) 20)
                .putFloat("AbsorptionAmount", 4.0f)
                .putDouble("Score", 1500.5)
                .putBoolean("OnGround", true)
                .putByteArray("CustomFlags", new byte[]{0x01, 0x02, 0x04})
                .putIntArray("UUID", new int[]{1111, 2222, 3333, 4444})
                .beginCompound("Attributes")
                    .putFloat("generic.max_health", 20.0f)
                    .putFloat("generic.movement_speed", 0.1f)
                    .putBoolean("isFlying", false)
                .endCompound()
                .beginList("Pos", NbtConstants.TAG_DOUBLE)
                    .addDouble(100.5)
                    .addDouble(64.0)
                    .addDouble(-250.25)
                .endList()
                .beginList("Inventory", NbtConstants.TAG_COMPOUND)
                    .beginCompound()
                        .putByte("Slot", (byte) 0)
                        .putString("id", "minecraft:diamond_sword")
                        .putByte("Count", (byte) 1)
                        .beginCompound("tag")
                            .putInt("Damage", 10)
                            .putBoolean("Unbreakable", true)
                        .endCompound()
                    .endCompound()
                    .beginCompound()
                        .putByte("Slot", (byte) 1)
                        .putString("id", "minecraft:golden_apple")
                        .putByte("Count", (byte) 64)
                    .endCompound()
                .endList()
                .endRoot();

        byte[] binaryNbt = writer.toByteArray();
        System.out.printf("Written NBT raw byte size: %d bytes%n", binaryNbt.length);

        System.out.println("\n=== 2. Reading NBT data using FastNbtReader (Zero-Allocation Indexed Access) ===");

        FastNbtReader reader = new FastNbtReader(binaryNbt);
        FastNbtReader.FastCompound root = reader.readRootCompound();

        System.out.println("PlayerName: " + root.getString("PlayerName", "Unknown"));
        System.out.println("DataVersion: " + root.getInt("DataVersion", 0));
        System.out.println("Health: " + root.getShort("Health", (short) 0));
        System.out.println("OnGround: " + root.getBoolean("OnGround", false));

        FastNbtReader.FastCompound attributes = root.getCompound("Attributes");
        if (attributes != null) {
            System.out.println("Attributes.max_health: " + attributes.getFloat("generic.max_health", 0.0f));
            System.out.println("Attributes.isFlying: " + attributes.getBoolean("isFlying", false));
        }

        FastNbtReader.FastList posList = root.getList("Pos");
        if (posList != null) {
            System.out.printf("Position: [x=%.2f, y=%.2f, z=%.2f]%n",
                    posList.getDouble(0), posList.getDouble(1), posList.getDouble(2));
        }

        FastNbtReader.FastList inventory = root.getList("Inventory");
        if (inventory != null) {
            System.out.printf("Inventory items count: %d%n", inventory.size());
            for (int i = 0; i < inventory.size(); i++) {
                FastNbtReader.FastCompound item = inventory.getCompound(i);
                byte slot = item.getByte("Slot", (byte) -1);
                String id = item.getString("id", "unknown");
                byte count = item.getByte("Count", (byte) 0);
                System.out.printf("  - Slot %d: %s x%d%n", slot, id, count);

                FastNbtReader.FastCompound tag = item.getCompound("tag");
                if (tag != null) {
                    System.out.printf("    Damage: %d, Unbreakable: %b%n",
                            tag.getInt("Damage", 0), tag.getBoolean("Unbreakable", false));
                }
            }
        }

        System.out.println("\n=== 3. Reading existing compressed Minecraft .dat file ===");
        File datFile = new File("0ee0c13d-70e5-3031-ae99-c5b7b6d44331.dat");
        if (datFile.exists()) {
            byte[] decompressed = readCompressedFile(datFile);
            FastNbtReader fileReader = new FastNbtReader(decompressed);
            FastNbtReader.FastCompound fileRoot = fileReader.readRootCompound();
            System.out.printf("Loaded file: %s (uncompressed size: %d bytes, root fields: %d)%n",
                    datFile.getName(), decompressed.length, fileRoot.size());
            System.out.println("DataVersion in file: " + fileRoot.getInt("DataVersion", -1));
            System.out.println("Dimension in file: " + fileRoot.getString("Dimension", "N/A"));
        }
    }

    public static byte[] readCompressedFile(File file) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(file))) {
            return gis.readAllBytes();
        } catch (Exception ignored) {
            // If not GZIP compressed, read as plain raw bytes
            try (FileInputStream fis = new FileInputStream(file)) {
                return fis.readAllBytes();
            }
        }
    }

    public static void writeCompressedFile(File file, byte[] nbtBytes) throws IOException {
        try (GZIPOutputStream gos = new GZIPOutputStream(new FileOutputStream(file))) {
            gos.write(nbtBytes);
        }
    }
}
