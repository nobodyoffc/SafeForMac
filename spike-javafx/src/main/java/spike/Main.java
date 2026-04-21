package spike;

import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class Main extends Application {
    @Override
    public void start(Stage stage) {
        Label label = new Label("hello from javafx");
        StackPane root = new StackPane(label);
        root.setAlignment(Pos.CENTER);
        stage.setScene(new Scene(root, 400, 200));
        stage.setTitle("Safe Spike — JavaFX");
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
