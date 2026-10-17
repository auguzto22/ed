//! Ponte JNI escrita à mão (sem o crate `jni`, para compilar offline).
//!
//! Só usa a tabela de funções padrão da especificação JNI (os índices são fixos desde o JDK 1.2
//! e iguais no Android). Toda função exportada passa por [`guard`]: erros viram exceções Java e
//! um pânico nunca atravessa a fronteira FFI.

#![allow(non_snake_case, non_camel_case_types)]

use std::ffi::{c_char, c_void, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};

pub type jint = i32;
pub type jlong = i64;
pub type jfloat = f32;
pub type jboolean = u8;
pub type jsize = jint;
pub type jobject = *mut c_void;
pub type jclass = jobject;
pub type jstring = jobject;
pub type jarray = jobject;
pub type jlongArray = jarray;
pub type jfloatArray = jarray;
pub type jintArray = jarray;
pub type jshortArray = jarray;

/// `JNIEnv*`: ponteiro para o ponteiro da tabela de funções.
pub type RawEnv = *mut *const *const c_void;

// Índices da `JNINativeInterface_` (jni.h).
const FIND_CLASS: usize = 6;
const THROW_NEW: usize = 14;
const NEW_STRING_UTF: usize = 167;
const GET_ARRAY_LENGTH: usize = 171;
const NEW_INT_ARRAY: usize = 179;
const NEW_LONG_ARRAY: usize = 180;
const NEW_FLOAT_ARRAY: usize = 181;
const GET_SHORT_ARRAY_REGION: usize = 202;
const GET_INT_ARRAY_REGION: usize = 203;
const GET_LONG_ARRAY_REGION: usize = 204;
const GET_FLOAT_ARRAY_REGION: usize = 205;
const SET_SHORT_ARRAY_REGION: usize = 210;
const SET_INT_ARRAY_REGION: usize = 211;
const SET_LONG_ARRAY_REGION: usize = 212;
const SET_FLOAT_ARRAY_REGION: usize = 213;
const GET_PRIMITIVE_ARRAY_CRITICAL: usize = 222;
const RELEASE_PRIMITIVE_ARRAY_CRITICAL: usize = 223;
const EXCEPTION_CHECK: usize = 228;

/// Erro que vira exceção Java.
#[derive(Debug)]
pub enum JniError {
    IllegalArgument(String),
    IllegalState(String),
    /// Uma exceção Java já está pendente (por exemplo, falta de memória ao criar um array).
    Pending,
}

pub type JniResult<T> = Result<T, JniError>;

pub fn illegal(message: impl Into<String>) -> JniError {
    JniError::IllegalArgument(message.into())
}

#[derive(Clone, Copy)]
pub struct Env(RawEnv);

impl Env {
    /// # Safety
    /// `raw` deve ser o `JNIEnv*` recebido pela função nativa, na thread dela.
    pub unsafe fn from_raw(raw: RawEnv) -> Env {
        Env(raw)
    }

    unsafe fn function<T: Copy>(&self, index: usize) -> T {
        let table = *self.0;
        let entry = *table.add(index);
        debug_assert!(!entry.is_null());
        std::mem::transmute_copy::<*const c_void, T>(&entry)
    }

    pub fn exception_pending(&self) -> bool {
        unsafe {
            let f: extern "system" fn(RawEnv) -> jboolean = self.function(EXCEPTION_CHECK);
            f(self.0) != 0
        }
    }

    pub fn throw(&self, class: &str, message: &str) {
        if self.exception_pending() {
            return;
        }
        let class = CString::new(class).unwrap_or_default();
        let message = CString::new(message.replace('\0', " ")).unwrap_or_default();
        unsafe {
            let find: extern "system" fn(RawEnv, *const c_char) -> jclass = self.function(FIND_CLASS);
            let throw_new: extern "system" fn(RawEnv, jclass, *const c_char) -> jint = self.function(THROW_NEW);
            let cls = find(self.0, class.as_ptr());
            if !cls.is_null() {
                throw_new(self.0, cls, message.as_ptr());
            }
        }
    }

    pub fn length(&self, array: jarray) -> JniResult<usize> {
        if array.is_null() {
            return Err(illegal("array nulo"));
        }
        unsafe {
            let f: extern "system" fn(RawEnv, jarray) -> jsize = self.function(GET_ARRAY_LENGTH);
            Ok(f(self.0, array).max(0) as usize)
        }
    }

    /// Lê um `long[]` inteiro (cópia).
    pub fn longs(&self, array: jlongArray) -> JniResult<Vec<i64>> {
        let n = self.length(array)?;
        let mut out = vec![0i64; n];
        unsafe {
            let f: extern "system" fn(RawEnv, jarray, jsize, jsize, *mut i64) = self.function(GET_LONG_ARRAY_REGION);
            f(self.0, array, 0, n as jsize, out.as_mut_ptr());
        }
        self.check()?;
        Ok(out)
    }

    pub fn floats(&self, array: jfloatArray) -> JniResult<Vec<f32>> {
        let n = self.length(array)?;
        let mut out = vec![0f32; n];
        unsafe {
            let f: extern "system" fn(RawEnv, jarray, jsize, jsize, *mut f32) = self.function(GET_FLOAT_ARRAY_REGION);
            f(self.0, array, 0, n as jsize, out.as_mut_ptr());
        }
        self.check()?;
        Ok(out)
    }

    pub fn ints(&self, array: jintArray) -> JniResult<Vec<i32>> {
        let n = self.length(array)?;
        let mut out = vec![0i32; n];
        unsafe {
            let f: extern "system" fn(RawEnv, jarray, jsize, jsize, *mut i32) = self.function(GET_INT_ARRAY_REGION);
            f(self.0, array, 0, n as jsize, out.as_mut_ptr());
        }
        self.check()?;
        Ok(out)
    }

    /// Lê `count` floats a partir de `offset` para dentro de `out`.
    pub fn read_floats(&self, array: jfloatArray, offset: usize, out: &mut [f32]) -> JniResult<()> {
        let n = self.length(array)?;
        if offset + out.len() > n {
            return Err(illegal("fora dos limites do array"));
        }
        unsafe {
            let f: extern "system" fn(RawEnv, jarray, jsize, jsize, *mut f32) = self.function(GET_FLOAT_ARRAY_REGION);
            f(self.0, array, offset as jsize, out.len() as jsize, out.as_mut_ptr());
        }
        self.check()
    }

    pub fn write_floats(&self, array: jfloatArray, offset: usize, data: &[f32]) -> JniResult<()> {
        let n = self.length(array)?;
        if offset + data.len() > n {
            return Err(illegal("fora dos limites do array"));
        }
        unsafe {
            let f: extern "system" fn(RawEnv, jarray, jsize, jsize, *const f32) = self.function(SET_FLOAT_ARRAY_REGION);
            f(self.0, array, offset as jsize, data.len() as jsize, data.as_ptr());
        }
        self.check()
    }

    pub fn read_shorts(&self, array: jshortArray, offset: usize, out: &mut [i16]) -> JniResult<()> {
        let n = self.length(array)?;
        if offset + out.len() > n {
            return Err(illegal("fora dos limites do array"));
        }
        unsafe {
            let f: extern "system" fn(RawEnv, jarray, jsize, jsize, *mut i16) = self.function(GET_SHORT_ARRAY_REGION);
            f(self.0, array, offset as jsize, out.len() as jsize, out.as_mut_ptr());
        }
        self.check()
    }

    pub fn write_shorts(&self, array: jshortArray, offset: usize, data: &[i16]) -> JniResult<()> {
        let n = self.length(array)?;
        if offset + data.len() > n {
            return Err(illegal("fora dos limites do array"));
        }
        unsafe {
            let f: extern "system" fn(RawEnv, jarray, jsize, jsize, *const i16) = self.function(SET_SHORT_ARRAY_REGION);
            f(self.0, array, offset as jsize, data.len() as jsize, data.as_ptr());
        }
        self.check()
    }

    /// Acesso sem cópia a um `long[]` durante `body`. Dentro de `body` não se pode chamar a JNI
    /// nem bloquear (regra das seções críticas); por isso só cálculos puros.
    pub fn with_longs<R>(&self, array: jlongArray, body: impl FnOnce(&[i64]) -> R) -> JniResult<R> {
        let n = self.length(array)?;
        unsafe {
            let get: extern "system" fn(RawEnv, jarray, *mut jboolean) -> *mut c_void = self.function(GET_PRIMITIVE_ARRAY_CRITICAL);
            let release: extern "system" fn(RawEnv, jarray, *mut c_void, jint) = self.function(RELEASE_PRIMITIVE_ARRAY_CRITICAL);
            let pointer = get(self.0, array, std::ptr::null_mut());
            if pointer.is_null() {
                return Err(JniError::Pending);
            }
            let slice = std::slice::from_raw_parts(pointer as *const i64, n);
            // JNI_ABORT (2): nada a copiar de volta, o array só foi lido.
            let result = catch_unwind(AssertUnwindSafe(|| body(slice)));
            release(self.0, array, pointer, 2);
            result.map_err(|_| JniError::IllegalState("falha interna no motor de velocidade".into()))
        }
    }

    pub fn new_longs(&self, data: &[i64]) -> JniResult<jlongArray> {
        unsafe {
            let new: extern "system" fn(RawEnv, jsize) -> jarray = self.function(NEW_LONG_ARRAY);
            let set: extern "system" fn(RawEnv, jarray, jsize, jsize, *const i64) = self.function(SET_LONG_ARRAY_REGION);
            let array = new(self.0, data.len() as jsize);
            if array.is_null() {
                return Err(JniError::Pending);
            }
            set(self.0, array, 0, data.len() as jsize, data.as_ptr());
            self.check()?;
            Ok(array)
        }
    }

    pub fn new_floats(&self, data: &[f32]) -> JniResult<jfloatArray> {
        unsafe {
            let new: extern "system" fn(RawEnv, jsize) -> jarray = self.function(NEW_FLOAT_ARRAY);
            let set: extern "system" fn(RawEnv, jarray, jsize, jsize, *const f32) = self.function(SET_FLOAT_ARRAY_REGION);
            let array = new(self.0, data.len() as jsize);
            if array.is_null() {
                return Err(JniError::Pending);
            }
            set(self.0, array, 0, data.len() as jsize, data.as_ptr());
            self.check()?;
            Ok(array)
        }
    }

    pub fn new_ints(&self, data: &[i32]) -> JniResult<jintArray> {
        unsafe {
            let new: extern "system" fn(RawEnv, jsize) -> jarray = self.function(NEW_INT_ARRAY);
            let set: extern "system" fn(RawEnv, jarray, jsize, jsize, *const i32) = self.function(SET_INT_ARRAY_REGION);
            let array = new(self.0, data.len() as jsize);
            if array.is_null() {
                return Err(JniError::Pending);
            }
            set(self.0, array, 0, data.len() as jsize, data.as_ptr());
            self.check()?;
            Ok(array)
        }
    }

    /// Cria uma `String` Java. O texto é UTF-8 sem NUL nem caracteres fora do plano básico,
    /// então coincide com o "UTF-8 modificado" da JNI.
    pub fn new_string(&self, text: &str) -> JniResult<jstring> {
        let c = CString::new(text.replace('\0', " ")).map_err(|_| illegal("texto inválido"))?;
        unsafe {
            let f: extern "system" fn(RawEnv, *const c_char) -> jstring = self.function(NEW_STRING_UTF);
            let s = f(self.0, c.as_ptr());
            if s.is_null() { Err(JniError::Pending) } else { Ok(s) }
        }
    }

    fn check(&self) -> JniResult<()> {
        if self.exception_pending() { Err(JniError::Pending) } else { Ok(()) }
    }
}

/// Executa `body`, transformando erro e pânico em exceção Java; devolve `fallback` nesse caso.
pub fn guard<T>(raw: RawEnv, fallback: T, body: impl FnOnce(Env) -> JniResult<T>) -> T {
    let env = unsafe { Env::from_raw(raw) };
    match catch_unwind(AssertUnwindSafe(|| body(env))) {
        Ok(Ok(value)) => value,
        Ok(Err(JniError::IllegalArgument(message))) => {
            env.throw("java/lang/IllegalArgumentException", &message);
            fallback
        }
        Ok(Err(JniError::IllegalState(message))) => {
            env.throw("java/lang/IllegalStateException", &message);
            fallback
        }
        Ok(Err(JniError::Pending)) => fallback,
        Err(_) => {
            env.throw("java/lang/IllegalStateException", "falha interna no motor de velocidade");
            fallback
        }
    }
}

/// Handles nativos (ponteiros de `Box`) guardados como `long` no Kotlin.
pub fn into_handle<T>(value: T) -> jlong {
    Box::into_raw(Box::new(value)) as jlong
}

/// # Safety
/// `handle` deve ter vindo de [`into_handle`] com o mesmo `T` e ainda não ter sido liberado.
pub unsafe fn handle_mut<'a, T>(handle: jlong) -> JniResult<&'a mut T> {
    if handle == 0 {
        return Err(JniError::IllegalState("objeto nativo já liberado".into()));
    }
    Ok(&mut *(handle as *mut T))
}

/// # Safety
/// Mesmas condições de [`handle_mut`]; depois desta chamada o handle não pode ser usado.
pub unsafe fn drop_handle<T>(handle: jlong) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut T));
    }
}
