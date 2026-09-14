export const REDIRECT_DELAY_MS = 10000;

export function redirectLoadingState(elapsedMs, failed = false) {
    if (failed) return 'error';
    return elapsedMs >= REDIRECT_DELAY_MS ? 'delayed' : 'loading';
}
