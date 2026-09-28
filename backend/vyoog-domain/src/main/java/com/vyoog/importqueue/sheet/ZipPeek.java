package com.vyoog.importqueue.sheet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads one named entry out of a ZIP in memory — enough to tell .xlsx from .ods and to pull content.xml. */
final class ZipPeek {

    private ZipPeek() {}

    static boolean hasEntry(byte[] bytes, String name) {
        return entry(bytes, name) != null;
    }

    /** @return the entry's bytes, or null when the archive has no such entry or cannot be read. */
    static byte[] entry(byte[] bytes, String name) {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!e.getName().equals(name)) continue;
                var out = new ByteArrayOutputStream();
                zip.transferTo(out);
                return out.toByteArray();
            }
        } catch (Exception notReadable) {
            return null;
        }
        return null;
    }
}
