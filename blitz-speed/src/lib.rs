//! Motor de velocidade do Blitz.
//!
//! - [`curve`]: curva de velocidade contínua e sua integral exata.
//! - [`timemap`]: mapa tempo da timeline ↔ tempo da mídia, compartilhado por prévia e exportação.
//! - [`presets`]: curvas prontas.
//! - [`warp`]: reajuste de textos, legendas e itens do projeto quando a velocidade muda.
//! - [`scheduler`]: quais quadros emitir na cadência de saída (síntese ou acumulação).
//! - [`policy`]: qual técnica usar, por aparelho e situação.

pub mod curve;
pub mod policy;
pub mod presets;
pub mod scheduler;
pub mod timemap;
pub mod warp;

pub mod api;
pub mod jni;
pub mod gpu;
pub mod validate;
pub mod audio;
