package org.witness;

import java.nio.file.Path;
import org.witness.smt.store.Clock;
import org.witness.smt.service.WitnessService;
import org.witness.smt.web.WebServer;

/**
 * Demo entry point.
 * {@code ./gradlew run --args='--host 127.0.0.1 --port 5392'}
 */
public final class App {

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 5392;
        Path db = Path.of("witness-room.db");
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--host" -> host = args[i + 1];
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--db" -> db = Path.of(args[i + 1]);
                default -> { }
            }
        }
        WitnessService service = new WitnessService(db, Clock.system());
        new WebServer(service).start(host, port);
        System.out.println("Solver witness room listening on http://" + host + ":" + port);
    }

    private App() {
    }
}
