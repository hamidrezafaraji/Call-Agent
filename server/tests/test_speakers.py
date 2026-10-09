import numpy as np

from callagent import speakers
from callagent.speakers import AGENT, CUSTOMER


def test_loud_and_quiet_lines_get_labels():
    # salesperson into the mic (~ -15 dB), customer through the earpiece (~ -35 dB)
    levels = [-15, -36, -14, -34, -16, -35]
    assert speakers.label(levels) == [AGENT, CUSTOMER, AGENT, CUSTOMER, AGENT, CUSTOMER]


def test_similar_levels_stay_unlabelled():
    # a phone recorder that mixes both sides evenly: don't guess
    assert speakers.label([-20, -22, -19, -21]) == [None] * 4


def test_single_segment_is_unlabelled():
    assert speakers.label([-20]) == [None]


def test_loudness_of_audio_slices():
    rate = 16_000
    loud = 0.5 * np.sin(np.linspace(0, 2000, rate)).astype(np.float32)
    quiet = loud * 0.05
    audio = np.concatenate([loud, quiet])
    a = speakers.loudness_db(audio, 0.0, 1.0)
    b = speakers.loudness_db(audio, 1.0, 2.0)
    assert a - b > 20
    assert speakers.label([a, b, a, b]) == [AGENT, CUSTOMER, AGENT, CUSTOMER]
