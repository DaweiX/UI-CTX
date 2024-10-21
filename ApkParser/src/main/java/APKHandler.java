import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.jimple.infoflow.android.axml.ApkHandler;

import java.io.IOException;

public class APKHandler {
    private static final Logger logger = LoggerFactory.getLogger(APKHandler.class);
    private ApkHandler handler;
    public APKHandler(String apk) {
        try {
            handler = new ApkHandler(apk);
        } catch (IOException e) {
            logger.error(e.toString());
        }
    }

    public ApkHandler getHandler() {
        return handler;
    }
}
