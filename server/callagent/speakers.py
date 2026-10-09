"""Tell the salesperson's lines from the customer's in a one-channel call recording.

When the phone app records the call itself, the salesperson speaks straight into the
microphone and the customer is only picked up through the earpiece, so the customer's
lines are clearly quieter. We measure each Whisper segment's loudness, split the segments
into a loud and a quiet group, and label them -- but only when the two groups are far
apart; otherwise (e.g. a phone recorder that mixes both sides evenly) we leave them
unlabelled rather than guess.
"""
import math

AGENT = "agent"        # shown as "-"
CUSTOMER = "customer"  # shown as "+"

MIN_SEPARATION_DB = 6.0  # loud and quiet groups must differ at least this much


def loudness_db(samples, start: float, end: float, rate: int = 16_000) -> float:
    """RMS level of samples[start:end] (seconds) in dB."""
    a, b = int(start * rate), max(int(end * rate), int(start * rate) + 1)
    chunk = samples[a:b]
    if len(chunk) == 0:
        return -120.0
    rms = math.sqrt(float((chunk.astype("float64") ** 2).mean()))
    return 20 * math.log10(rms + 1e-9)


def label(levels: list[float]) -> list[str | None]:
    """Two-group split of segment loudness: loud = agent, quiet = customer."""
    if len(levels) < 2:
        return [None] * len(levels)
    lo, hi = min(levels), max(levels)
    if hi - lo < MIN_SEPARATION_DB:
        return [None] * len(levels)
    for _ in range(20):  # 1-D k-means with two centres
        mid = (lo + hi) / 2
        quiet = [x for x in levels if x < mid]
        loud = [x for x in levels if x >= mid]
        if not quiet or not loud:
            return [None] * len(levels)
        new_lo, new_hi = sum(quiet) / len(quiet), sum(loud) / len(loud)
        if (new_lo, new_hi) == (lo, hi):
            break
        lo, hi = new_lo, new_hi
    if hi - lo < MIN_SEPARATION_DB:
        return [None] * len(levels)
    mid = (lo + hi) / 2
    return [AGENT if x >= mid else CUSTOMER for x in levels]
