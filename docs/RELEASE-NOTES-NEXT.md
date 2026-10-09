# DiPlay next release notes

Changes through DiPlay 0.2.15 are documented in [0.2.15 release notes](RELEASE-NOTES-0.2.15.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

## Siri and calls

- Wireless calls and Siri send the head unit's microphone on head units without a MediaCodec Opus encoder: every Android 7.1–9 head unit, and Android 10+ firmware whose vendor ships only an Opus decoder. CarPlay sends both as Opus, so the microphone stopped with `stage=ENCODER` and the other side heard nothing. DiPlay now falls back to a bundled software Opus encoder ([Concentus](https://github.com/lostromb/concentus)) when the platform has none. It encodes at complexity 3 on an audio-priority capture thread, lowers the complexity if a head unit cannot keep up, and warms up in the background when a session starts so that the first Siri request is not cut short. The diagnostic report names the encoder for each microphone stream and adds its timing. Accepted on a BOS Mini A1 head unit (Android 9, MediaTek) with an iPhone 12 on iOS 27, where a 20 ms frame took 4.1 ms of CPU at complexity 3 and 8.6 ms at complexity 10. Related: [#415](https://github.com/shihabal3amri/DiPlay/issues/415)
