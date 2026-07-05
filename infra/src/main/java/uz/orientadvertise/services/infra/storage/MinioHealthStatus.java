package uz.orientadvertise.services.infra.storage;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

@Component
public class MinioHealthStatus {

    public enum State { UP, DEGRADED }

    private final AtomicReference<State> state = new AtomicReference<>(State.DEGRADED);

    public void markUp() {
        state.set(State.UP);
    }

    public void markDegraded() {
        state.set(State.DEGRADED);
    }

    public boolean isAvailable() {
        return state.get() == State.UP;
    }

    public State getState() {
        return state.get();
    }
}
