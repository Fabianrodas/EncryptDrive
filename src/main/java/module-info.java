module com.fabianrodas.encryptdrive {
    requires javafx.controls;
    requires javafx.fxml;
    requires com.google.gson;
    requires org.bouncycastle.provider;

    opens com.fabianrodas.encryptdrive to javafx.fxml;
    opens com.fabianrodas.models to com.google.gson;

    exports com.fabianrodas.encryptdrive;
}