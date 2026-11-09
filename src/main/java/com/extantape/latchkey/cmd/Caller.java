package com.extantape.latchkey.cmd;

/** Whoever ran a command. */
public interface Caller {
    boolean has(String permission);

    /** A player in game. Their chat isn't written to the server log, so a secret may go to them directly. */
    boolean inGame();

    /** A line for a person. */
    void tell(String text, boolean problem);

    /** Raw text, for Signalbox answers. */
    void line(String raw);
}
