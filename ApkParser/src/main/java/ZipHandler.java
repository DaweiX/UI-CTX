import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ZipHandler {
    private ZipFile zip;
    private final File apk;
    private static final Logger logger = LoggerFactory.getLogger(ZipHandler.class);
    ArrayList<String> files;

    public ZipHandler(String apk) {
        this.apk = new File(apk);
    }

    public InputStream getInputStream(String filename) throws IOException {
        ZipEntry entry = this.zip.getEntry(filename);
        return this.zip.getInputStream(entry);
    }

    public ArrayList<String> listFiles() {
        if (files != null) {
            return files;
        }
        files = new ArrayList<>();
        if (this.zip == null) {
            try {
                this.zip = new ZipFile(this.apk);
            } catch (IOException e) {
                logger.error("bad zip file");
            }
        }
        Enumeration<?> entries = this.zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = (ZipEntry) entries.nextElement();
            String entryName = entry.getName();
            files.add(entryName);
        }
        return files;
    }
}
