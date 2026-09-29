/* Host-side GLES test of the exact shader assets shipped in the APK. */
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static char *read_source(const char *path) {
    FILE *f = fopen(path, "rb"); assert(f);
    fseek(f, 0, SEEK_END); long length = ftell(f); rewind(f);
    char *source = calloc(length + 1, 1); assert(source);
    assert(fread(source, 1, length, f) == (size_t)length); fclose(f);
    return source;
}
static GLuint compile(GLenum kind, const char *source, const char *path) {
    GLuint id = glCreateShader(kind); glShaderSource(id, 1, (const GLchar **)&source, NULL); glCompileShader(id);
    GLint ok; glGetShaderiv(id, GL_COMPILE_STATUS, &ok);
    if (!ok) { char log[4096]; glGetShaderInfoLog(id, sizeof(log), NULL, log); fprintf(stderr, "%s: %s\n", path, log); exit(1); }
    return id;
}
static GLuint shader(GLenum kind, const char *path) {
    char *source = read_source(path); GLuint id = compile(kind, source, path); free(source); return id;
}
static void package_effects(GLuint vertex, GLuint texture, GLfloat *vertices) {
    const char *ids[] = {"recly_glow", "recly_pixel", "recly_rgb", "recly_scanlines", "recly_shake", "recly_directional_blur"};
    const char *params[] = {"radius", "threshold", "size", "distance", "angle", "spacing", "speed", "frequency"};
    const float values[] = {8, .4f, 12, 8, 0, 3, 10, 4};
    char *header = read_source("app/src/main/res/raw/effect_header.glsl");
    char *footer = read_source("app/src/main/res/raw/effect_footer.glsl");
    unsigned char original[4096], rendered[4096];
    for (int i = 0; i < 1024; i++) {
        original[4*i] = (i * 17) % 256; original[4*i+1] = (i * 31) % 256;
        original[4*i+2] = (i * 7) % 256; original[4*i+3] = 255;
    }
    glDisable(GL_BLEND); glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 32, 32, 0, GL_RGBA, GL_UNSIGNED_BYTE, original);
    for (int effect = 0; effect < 6; effect++) {
        char path[256]; snprintf(path, sizeof(path), "app/src/main/assets/editor/effects/%s.frag", ids[effect]);
        char *body = read_source(path);
        size_t size = strlen(header) + strlen(footer) + strlen(body) + 1024;
        char *source = calloc(size, 1); assert(source); strcat(source, header); strcat(source, "\n");
        for (int p = 0; p < 8; p++) { strcat(source, "uniform float p_"); strcat(source, params[p]); strcat(source, ";\n"); }
        strcat(source, body); strcat(source, "\n"); strcat(source, footer);
        GLuint fragment = compile(GL_FRAGMENT_SHADER, source, path); free(body); free(source);
        GLuint program = glCreateProgram(); glAttachShader(program, vertex); glAttachShader(program, fragment); glLinkProgram(program);
        GLint linked; glGetProgramiv(program, GL_LINK_STATUS, &linked); assert(linked); glUseProgram(program);
        GLint position = glGetAttribLocation(program, "aFramePosition"); assert(position >= 0);
        glEnableVertexAttribArray(position); glVertexAttribPointer(position, 4, GL_FLOAT, GL_FALSE, 0, vertices);
        glUniform1i(glGetUniformLocation(program, "uTexSampler"), 0);
        glUniform2f(glGetUniformLocation(program, "uResolution"), 32, 32);
        glUniform1f(glGetUniformLocation(program, "uTime"), .123f);
        glUniform1f(glGetUniformLocation(program, "uProgress"), .5f);
        for (int p = 0; p < 8; p++) { char uniform[64]; snprintf(uniform, sizeof(uniform), "p_%s", params[p]); glUniform1f(glGetUniformLocation(program, uniform), values[p]); }
        glUniform1f(glGetUniformLocation(program, "uIntensity"), 0); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glReadPixels(0, 0, 32, 32, GL_RGBA, GL_UNSIGNED_BYTE, rendered);
        for (int i = 0; i < 4096; i++) assert(abs(rendered[i] - original[i]) <= 1);
        glUniform1f(glGetUniformLocation(program, "uIntensity"), 1); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glReadPixels(0, 0, 32, 32, GL_RGBA, GL_UNSIGNED_BYTE, rendered);
        int changed = 0;
        for (int i = 0; i < 4096; i++) { if (abs(rendered[i] - original[i]) > 2) changed++; if (i % 4 == 3) assert(rendered[i] == 255); }
        assert(changed > 16); assert(glGetError() == GL_NO_ERROR);
        printf("PASS: %s compilation, zero intensity identity, nonzero pixel effect, alpha\n", ids[effect]);
        glDeleteProgram(program); glDeleteShader(fragment);
    }
    free(header); free(footer);
}
static void pixel(int x, int y, int r, int g, int b) {
    unsigned char p[4]; glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, p);
    if (abs(p[0] - r) > 2 || abs(p[1] - g) > 2 || abs(p[2] - b) > 2 || p[3] != 255) {
        fprintf(stderr, "Pixel %d,%d: %d %d %d %d; expected %d %d %d 255\n", x, y, p[0], p[1], p[2], p[3], r, g, b); exit(1);
    }
}
int main(void) {
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY); assert(display != EGL_NO_DISPLAY);
    assert(eglInitialize(display, NULL, NULL)); assert(eglBindAPI(EGL_OPENGL_ES_API));
    EGLint attributes[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig config; EGLint count; assert(eglChooseConfig(display, attributes, &config, 1, &count) && count);
    EGLint pbuffer[] = {EGL_WIDTH, 32, EGL_HEIGHT, 32, EGL_NONE};
    EGLSurface surface = eglCreatePbufferSurface(display, config, pbuffer); assert(surface != EGL_NO_SURFACE);
    EGLint client[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, client); assert(context != EGL_NO_CONTEXT);
    assert(eglMakeCurrent(display, surface, surface, context));
    GLuint program = glCreateProgram();
    GLuint vertex = shader(GL_VERTEX_SHADER, "app/src/main/res/raw/studio_vertex.glsl");
    GLuint fragment = shader(GL_FRAGMENT_SHADER, "app/src/main/res/raw/studio_fragment.glsl");
    glAttachShader(program, vertex); glAttachShader(program, fragment); glLinkProgram(program);
    GLint linked; glGetProgramiv(program, GL_LINK_STATUS, &linked); assert(linked); glUseProgram(program);
    GLfloat vertices[] = {-1,-1,0,1, -1,1,0,1, 1,-1,0,1, 1,1,0,1};
    GLint position = glGetAttribLocation(program, "aFramePosition"); assert(position >= 0);
    glEnableVertexAttribArray(position); glVertexAttribPointer(position, 4, GL_FLOAT, GL_FALSE, 0, vertices);
    unsigned char colors[32 * 32 * 4];
    for (int i = 0; i < 32 * 32; i++) { colors[4*i] = 64; colors[4*i+1] = 128; colors[4*i+2] = 192; colors[4*i+3] = 255; }
    GLuint texture; glGenTextures(1, &texture); glBindTexture(GL_TEXTURE_2D, texture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 32, 32, 0, GL_RGBA, GL_UNSIGNED_BYTE, colors);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glUniform1i(glGetUniformLocation(program, "uTexSampler"), 0);
    glUniform2f(glGetUniformLocation(program, "uTexel"), 1.f/32, 1.f/32);
    glUniform4f(glGetUniformLocation(program, "uCurve"), 0, .25, .5, .75);
    glUniform1f(glGetUniformLocation(program, "uCurveEnd"), 1);
    glUniform4f(glGetUniformLocation(program, "uChannel0"), 0, .25, .5, .75);
    glUniform4f(glGetUniformLocation(program, "uChannel1"), 0, .25, .5, .75);
    glUniform4f(glGetUniformLocation(program, "uChannel2"), 0, .25, .5, .75);
    glUniform3f(glGetUniformLocation(program, "uChannelEnds"), 1, 1, 1);
    glUniform4f(glGetUniformLocation(program, "uMaskTransform"), 0, 0, 0, 1);
    glUniform1f(glGetUniformLocation(program, "uMaskOpacity"), 1);
    glUniform3f(glGetUniformLocation(program, "uKeyEdge"), .08, 0, 0);
    glUniform1f(glGetUniformLocation(program, "uOpacity"), 1);
    glViewport(0, 0, 32, 32); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    pixel(16, 16, 64, 128, 192);
    glUniform3f(glGetUniformLocation(program, "uLight"), 1, 0, 0); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    pixel(16, 16, 128, 255, 255);
    glUniform3f(glGetUniformLocation(program, "uLight"), 0, 0, 0);
    GLuint lut; glGenTextures(1, &lut); glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, lut);
    unsigned char lookup[8 * 4];
    for (int r = 0; r < 2; r++) for (int g = 0; g < 2; g++) for (int b = 0; b < 2; b++) {
        int offset = (b + 2 * (g + 2 * r)) * 4;
        lookup[offset] = r * 255; lookup[offset+1] = g * 255; lookup[offset+2] = b * 255; lookup[offset+3] = 255;
    }
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 2, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, lookup);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glUniform1i(glGetUniformLocation(program, "uLut"), 1); glUniform1f(glGetUniformLocation(program, "uLutSize"), 2);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); pixel(16, 16, 64, 128, 192);
    glUniform1f(glGetUniformLocation(program, "uLutSize"), 0);
    glUniform1f(glGetUniformLocation(program, "uOpacity"), .5); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); pixel(16, 16, 32, 64, 96);
    glUniform1f(glGetUniformLocation(program, "uOpacity"), 1);
    glUniform4f(glGetUniformLocation(program, "uMask"), 1, .5, .001, 0); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    pixel(16, 16, 64, 128, 192); pixel(0, 0, 0, 0, 0);
    glUniform4f(glGetUniformLocation(program, "uMask"), 1, .5, .001, 1); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    pixel(16, 16, 0, 0, 0); pixel(0, 0, 64, 128, 192);
    glUniform4f(glGetUniformLocation(program, "uMask"), 0, .5, .001, 0);
    glUniform4f(glGetUniformLocation(program, "uChannel0"), 0, 0, 0, 0);
    glUniform3f(glGetUniformLocation(program, "uChannelEnds"), 0, 1, 1);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); pixel(16, 16, 0, 128, 192);
    glUniform4f(glGetUniformLocation(program, "uChannel0"), 0, .25, .5, .75);
    glUniform3f(glGetUniformLocation(program, "uChannelEnds"), 1, 1, 1);
    for (int band = 0; band < 8; band++) {
        char name[24]; snprintf(name, sizeof(name), "uHsl%d", band);
        glUniform3f(glGetUniformLocation(program, name), 0, -1, 0);
    }
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    unsigned char neutral[4]; glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, neutral);
    assert(abs(neutral[0] - neutral[1]) < 3 && abs(neutral[1] - neutral[2]) < 3);
    for (int band = 0; band < 8; band++) {
        char name[24]; snprintf(name, sizeof(name), "uHsl%d", band);
        glUniform3f(glGetUniformLocation(program, name), 0, 0, 0);
    }
    for (int mask = 4; mask <= 8; mask++) {
        glUniform4f(glGetUniformLocation(program, "uMask"), mask, .5, .001, 0);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        unsigned char pixels[4096]; glReadPixels(0, 0, 32, 32, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        int dark = 0, visible = 0;
        for (int i = 0; i < 1024; i++) { if (pixels[4*i] < 2) dark++; if (pixels[4*i] > 60) visible++; }
        assert(dark > 50 && visible > 50);
    }
    glUniform4f(glGetUniformLocation(program, "uMask"), 1, .3, .001, 0);
    glUniform4f(glGetUniformLocation(program, "uMaskTransform"), .3, 0, 0, 1);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); pixel(16, 16, 0, 0, 0); pixel(25, 16, 64, 128, 192);
    glUniform1f(glGetUniformLocation(program, "uMaskOpacity"), 0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); pixel(16, 16, 64, 128, 192);
    glUniform1f(glGetUniformLocation(program, "uMaskOpacity"), 1);
    glUniform4f(glGetUniformLocation(program, "uMaskTransform"), 0, 0, 0, 1);
    glUniform4f(glGetUniformLocation(program, "uMask"), 0, .5, .001, 0);
    glUniform4f(glGetUniformLocation(program, "uChroma"), powf(64.f/255, 1.f/2.2f), powf(128.f/255, 1.f/2.2f), powf(192.f/255, 1.f/2.2f), .2);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); pixel(16, 16, 0, 0, 0);
    /* A removed foreground must reveal the lower video, not a black rectangle. */
    glUniform1f(glGetUniformLocation(program, "uPreserveAlpha"), 1);
    glEnable(GL_BLEND);
    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glClearColor(1, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); pixel(16, 16, 255, 0, 0);
    glUniform4f(glGetUniformLocation(program, "uChroma"), 0, 1, 0, 0);
    glUniform1f(glGetUniformLocation(program, "uOpacity"), .5);
    glClearColor(0, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); pixel(16, 16, 32, 64, 96);
    assert(glGetError() == GL_NO_ERROR);
    package_effects(vertex, texture, vertices);
    glDeleteProgram(program); glDeleteShader(vertex); glDeleteShader(fragment); glDeleteTextures(1, &texture); glDeleteTextures(1, &lut);
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(display, context); eglDestroySurface(display, surface); eglTerminate(display);
    puts("PASS: shader, identity, exposure, LUT, RGB curves, HSL, opacity, nine mask modes, mask position/intensity, chroma, multi-layer alpha blend");
    return 0;
}
