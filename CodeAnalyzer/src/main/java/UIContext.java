@SuppressWarnings("unused")
public class UIContext {
    public static class ArscObj {
        int resourceID;
        String resourceName;
        String value;
        public int getResourceID() {
            return resourceID;
        }

        public void setResourceID(int resourceID) {
            this.resourceID = resourceID;
        }

        public String getResourceName() {
            return resourceName;
        }

        public void setResourceName(String resourceName) {
            this.resourceName = resourceName;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
