//! OpenGL ES 2.0 carregado em tempo de execução (`dlopen`).
//!
//! Nada é ligado ao GL em tempo de link: a mesma biblioteca roda nos testes da JVM em máquinas
//! sem GPU, e o pipeline só falha (com erro claro) se for criado onde não há GLES. No Android o
//! `libGLESv2.so` é biblioteca pública do NDK.

#![allow(non_snake_case, clippy::missing_safety_doc)]

use std::ffi::{c_char, c_int, c_void, CStr};
use std::sync::OnceLock;

pub type GLenum = u32;
pub type GLuint = u32;
pub type GLint = i32;
pub type GLsizei = i32;
pub type GLfloat = f32;
pub type GLboolean = u8;
pub type GLbitfield = u32;

pub const TEXTURE_2D: GLenum = 0x0DE1;
pub const TEXTURE0: GLenum = 0x84C0;
pub const TEXTURE_MIN_FILTER: GLenum = 0x2801;
pub const TEXTURE_MAG_FILTER: GLenum = 0x2800;
pub const TEXTURE_WRAP_S: GLenum = 0x2802;
pub const TEXTURE_WRAP_T: GLenum = 0x2803;
pub const NEAREST: GLint = 0x2600;
pub const LINEAR: GLint = 0x2601;
pub const CLAMP_TO_EDGE: GLint = 0x812F;
pub const RGBA: GLenum = 0x1908;
pub const UNSIGNED_BYTE: GLenum = 0x1401;
pub const FLOAT: GLenum = 0x1406;
pub const FRAMEBUFFER: GLenum = 0x8D40;
pub const COLOR_ATTACHMENT0: GLenum = 0x8CE0;
pub const FRAMEBUFFER_COMPLETE: GLenum = 0x8CD5;
pub const FRAMEBUFFER_BINDING: GLenum = 0x8CA6;
pub const CURRENT_PROGRAM: GLenum = 0x8B8D;
pub const VIEWPORT: GLenum = 0x0BA2;
pub const ACTIVE_TEXTURE: GLenum = 0x84E0;
pub const TEXTURE_BINDING_2D: GLenum = 0x8069;
pub const ARRAY_BUFFER: GLenum = 0x8892;
pub const ARRAY_BUFFER_BINDING: GLenum = 0x8894;
pub const BLEND: GLenum = 0x0BE2;
pub const SCISSOR_TEST: GLenum = 0x0C11;
pub const DEPTH_TEST: GLenum = 0x0B71;
pub const ONE: GLenum = 1;
pub const ZERO: GLenum = 0;
pub const CONSTANT_ALPHA: GLenum = 0x8003;
pub const ONE_MINUS_CONSTANT_ALPHA: GLenum = 0x8004;
pub const TRIANGLE_STRIP: GLenum = 0x0005;
pub const VERTEX_SHADER: GLenum = 0x8B31;
pub const FRAGMENT_SHADER: GLenum = 0x8B30;
pub const COMPILE_STATUS: GLenum = 0x8B81;
pub const LINK_STATUS: GLenum = 0x8B82;
pub const INFO_LOG_LENGTH: GLenum = 0x8B84;
pub const COLOR_BUFFER_BIT: GLbitfield = 0x4000;
pub const MAX_TEXTURE_SIZE: GLenum = 0x0D33;
pub const HIGH_FLOAT: GLenum = 0x8DF2;
pub const NO_ERROR: GLenum = 0;
pub const UNPACK_ALIGNMENT: GLenum = 0x0CF5;
pub const PACK_ALIGNMENT: GLenum = 0x0D05;

macro_rules! gl_api {
    ($($name:ident : fn($($arg:ty),*) $(-> $ret:ty)?;)*) => {
        /// Tabela de funções do GLES2.
        pub struct Gl {
            $(pub $name: unsafe extern "C" fn($($arg),*) $(-> $ret)?,)*
        }
        impl Gl {
            unsafe fn load(library: *mut c_void) -> Result<Gl, String> {
                Ok(Gl {
                    $($name: {
                        let symbol = concat!("gl", stringify!($name), "\0");
                        let pointer = dlsym(library, symbol.as_ptr() as *const c_char);
                        if pointer.is_null() {
                            return Err(format!("função GLES ausente: gl{}", stringify!($name)));
                        }
                        std::mem::transmute::<*mut c_void, unsafe extern "C" fn($($arg),*) $(-> $ret)?>(pointer)
                    },)*
                })
            }
        }
    };
}

gl_api! {
    ActiveTexture: fn(GLenum);
    AttachShader: fn(GLuint, GLuint);
    BindFramebuffer: fn(GLenum, GLuint);
    BindBuffer: fn(GLenum, GLuint);
    BindTexture: fn(GLenum, GLuint);
    BlendColor: fn(GLfloat, GLfloat, GLfloat, GLfloat);
    BlendFunc: fn(GLenum, GLenum);
    CheckFramebufferStatus: fn(GLenum) -> GLenum;
    Clear: fn(GLbitfield);
    ClearColor: fn(GLfloat, GLfloat, GLfloat, GLfloat);
    CompileShader: fn(GLuint);
    CreateProgram: fn() -> GLuint;
    CreateShader: fn(GLenum) -> GLuint;
    DeleteFramebuffers: fn(GLsizei, *const GLuint);
    DeleteProgram: fn(GLuint);
    DeleteShader: fn(GLuint);
    DeleteTextures: fn(GLsizei, *const GLuint);
    Disable: fn(GLenum);
    DisableVertexAttribArray: fn(GLuint);
    DrawArrays: fn(GLenum, GLint, GLsizei);
    Enable: fn(GLenum);
    EnableVertexAttribArray: fn(GLuint);
    FramebufferTexture2D: fn(GLenum, GLenum, GLenum, GLuint, GLint);
    GenFramebuffers: fn(GLsizei, *mut GLuint);
    GenTextures: fn(GLsizei, *mut GLuint);
    GetAttribLocation: fn(GLuint, *const c_char) -> GLint;
    GetError: fn() -> GLenum;
    GetIntegerv: fn(GLenum, *mut GLint);
    IsEnabled: fn(GLenum) -> GLboolean;
    GetProgramInfoLog: fn(GLuint, GLsizei, *mut GLsizei, *mut c_char);
    GetProgramiv: fn(GLuint, GLenum, *mut GLint);
    GetShaderInfoLog: fn(GLuint, GLsizei, *mut GLsizei, *mut c_char);
    GetShaderPrecisionFormat: fn(GLenum, GLenum, *mut GLint, *mut GLint);
    GetShaderiv: fn(GLuint, GLenum, *mut GLint);
    GetUniformLocation: fn(GLuint, *const c_char) -> GLint;
    LinkProgram: fn(GLuint);
    PixelStorei: fn(GLenum, GLint);
    ReadPixels: fn(GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, *mut c_void);
    ShaderSource: fn(GLuint, GLsizei, *const *const c_char, *const GLint);
    TexImage2D: fn(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, *const c_void);
    TexParameteri: fn(GLenum, GLenum, GLint);
    Uniform1f: fn(GLint, GLfloat);
    Uniform1i: fn(GLint, GLint);
    Uniform2f: fn(GLint, GLfloat, GLfloat);
    UseProgram: fn(GLuint);
    VertexAttribPointer: fn(GLuint, GLint, GLenum, GLboolean, GLsizei, *const c_void);
    Viewport: fn(GLint, GLint, GLsizei, GLsizei);
}

#[cfg_attr(target_os = "android", link(name = "dl"))]
extern "C" {
    fn dlopen(filename: *const c_char, flag: c_int) -> *mut c_void;
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
}

const RTLD_NOW: c_int = 2;

static GL: OnceLock<Result<Gl, String>> = OnceLock::new();

/// A tabela GLES do processo (carregada uma vez).
pub fn gl() -> Result<&'static Gl, String> {
    GL.get_or_init(|| unsafe {
        for name in [c"libGLESv2.so", c"libGLESv2.so.2"] {
            let library = dlopen(name.as_ptr(), RTLD_NOW);
            if !library.is_null() {
                return Gl::load(library);
            }
        }
        Err("libGLESv2 não encontrada".to_string())
    })
    .as_ref()
    .map_err(|e| e.clone())
}

/// Capacidades relevantes do contexto atual.
#[derive(Clone, Copy, Debug)]
pub struct Capabilities {
    pub highp_fragment: bool,
    pub max_texture_size: i32,
}

/// Precisa de um contexto atual na thread.
pub fn probe() -> Result<Capabilities, String> {
    let gl = gl()?;
    unsafe {
        let mut range = [0 as GLint; 2];
        let mut precision: GLint = 0;
        (gl.GetShaderPrecisionFormat)(FRAGMENT_SHADER, HIGH_FLOAT, range.as_mut_ptr(), &mut precision);
        let mut max: GLint = 0;
        (gl.GetIntegerv)(MAX_TEXTURE_SIZE, &mut max);
        // highp de verdade: mantissa de pelo menos 23 bits (precision = log2 da precisão relativa).
        Ok(Capabilities { highp_fragment: precision >= 16 && range[1] >= 62, max_texture_size: max })
    }
}

pub fn info_log(gl: &Gl, object: GLuint, program: bool) -> String {
    unsafe {
        let mut length: GLint = 0;
        if program { (gl.GetProgramiv)(object, INFO_LOG_LENGTH, &mut length) } else { (gl.GetShaderiv)(object, INFO_LOG_LENGTH, &mut length) }
        let mut buffer = vec![0u8; length.max(1) as usize + 1];
        if program {
            (gl.GetProgramInfoLog)(object, buffer.len() as GLsizei, std::ptr::null_mut(), buffer.as_mut_ptr() as *mut c_char)
        } else {
            (gl.GetShaderInfoLog)(object, buffer.len() as GLsizei, std::ptr::null_mut(), buffer.as_mut_ptr() as *mut c_char)
        }
        CStr::from_bytes_until_nul(&buffer).map(|s| s.to_string_lossy().into_owned()).unwrap_or_default()
    }
}
