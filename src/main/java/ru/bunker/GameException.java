package ru.bunker;

public class GameException extends RuntimeException {
    private final int status;

    public GameException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
