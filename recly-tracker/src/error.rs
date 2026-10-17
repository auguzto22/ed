//! Tipo de erro único do crate (sem dependências externas).

use std::fmt;

#[derive(Debug, Clone, PartialEq)]
pub struct Error(pub String);

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Error {}

pub type Result<T> = std::result::Result<T, Error>;

macro_rules! from_display {
    ($($t:ty),*) => {$(
        impl From<$t> for Error {
            fn from(e: $t) -> Self { Error(e.to_string()) }
        }
    )*};
}

from_display!(std::io::Error, std::string::FromUtf16Error, std::str::Utf8Error, std::num::ParseFloatError, std::num::ParseIntError, String, &str);

impl From<crate::track::ValidationError> for Error {
    fn from(e: crate::track::ValidationError) -> Self {
        Error(e.0)
    }
}

/// Acrescenta contexto a um erro, como `anyhow::Context`.
pub trait Context<T> {
    fn context(self, msg: impl fmt::Display) -> Result<T>;
}

impl<T, E: fmt::Display> Context<T> for std::result::Result<T, E> {
    fn context(self, msg: impl fmt::Display) -> Result<T> {
        self.map_err(|e| Error(format!("{msg}: {e}")))
    }
}

impl<T> Context<T> for Option<T> {
    fn context(self, msg: impl fmt::Display) -> Result<T> {
        self.ok_or_else(|| Error(msg.to_string()))
    }
}

#[macro_export]
macro_rules! bail {
    ($($t:tt)*) => { return Err($crate::error::Error(format!($($t)*))) };
}

#[macro_export]
macro_rules! ensure {
    ($cond:expr, $($t:tt)*) => { if !$cond { $crate::bail!($($t)*) } };
}
