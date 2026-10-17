//! Conferência do arquivo exportado: duração do vídeo e sincronia do áudio.
//!
//! O MP4 é lido depois de pronto (no Kotlin, pelo `MediaExtractor`) e as durações das trilhas
//! chegam aqui. A trilha de vídeo dura até o fim do último quadro, então a tolerância é de dois
//! quadros mais 40 ms; o áudio pode diferir do vídeo por até 100 ms (um quadro AAC e o
//! arredondamento do encoder ficam bem abaixo disso).

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Verdict {
    Ok = 0,
    VideoTooShort = 1,
    VideoTooLong = 2,
    AudioMissing = 3,
    AudioOutOfSync = 4,
}

pub fn check(expected_us: i64, video_us: i64, audio_us: Option<i64>, expect_audio: bool, fps: i32) -> (Verdict, i64) {
    let frame_us = 1_000_000 / fps.max(1) as i64;
    let tolerance = 2 * frame_us + 40_000;
    let difference = video_us - expected_us;
    if difference < -tolerance {
        return (Verdict::VideoTooShort, difference);
    }
    if difference > tolerance {
        return (Verdict::VideoTooLong, difference);
    }
    match audio_us {
        None if expect_audio => (Verdict::AudioMissing, 0),
        Some(audio) if (audio - video_us).abs() > 100_000 => (Verdict::AudioOutOfSync, audio - video_us),
        _ => (Verdict::Ok, difference),
    }
}

pub fn text(verdict: Verdict, difference_us: i64) -> String {
    let ms = difference_us.abs() / 1000;
    match verdict {
        Verdict::Ok => "Duração e sincronia conferidas.".to_string(),
        Verdict::VideoTooShort => format!("O vídeo exportado ficou {ms} ms mais curto que o projeto."),
        Verdict::VideoTooLong => format!("O vídeo exportado ficou {ms} ms mais longo que o projeto."),
        Verdict::AudioMissing => "O áudio esperado não está no arquivo exportado.".to_string(),
        Verdict::AudioOutOfSync => format!("O áudio terminou {ms} ms {} do vídeo.", if difference_us > 0 { "depois" } else { "antes" }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_correct_file_passes() {
        assert_eq!(check(10_000_000, 10_033_333, Some(10_010_000), true, 30).0, Verdict::Ok);
        assert_eq!(check(10_000_000, 9_990_000, None, false, 30).0, Verdict::Ok);
    }

    #[test]
    fn wrong_durations_and_missing_or_late_audio_are_reported() {
        assert_eq!(check(10_000_000, 9_500_000, None, false, 30).0, Verdict::VideoTooShort);
        assert_eq!(check(10_000_000, 12_000_000, None, false, 30).0, Verdict::VideoTooLong);
        assert_eq!(check(10_000_000, 10_000_000, None, true, 30).0, Verdict::AudioMissing);
        let (verdict, diff) = check(10_000_000, 10_000_000, Some(9_700_000), true, 30);
        assert_eq!((verdict, diff), (Verdict::AudioOutOfSync, -300_000));
        assert!(text(verdict, diff).contains("300 ms antes"));
    }
}
