package work.alberton.droidhand.shell;

/** One short-lived, on-device input session dedicated to a single unlock. */
interface IShellUnlockInput {
    boolean prepareKeyguard();
    boolean submitPin(in int[] digits);
}
