module com.fabianrodas.encryptdrive {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.base;

    opens com.fabianrodas.encryptdrive to javafx.fxml;
    exports com.fabianrodas.encryptdrive;
}
