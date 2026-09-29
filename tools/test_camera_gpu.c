/* Pixel regression: exact production shaders, shared Kotlin camera matrices, real GLES draws. */
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "shaders.h"
#define W 320
#define H 180
#define BYTES (W*H*4)
static GLuint compile(GLenum kind, const char *src) {
    GLuint shader = glCreateShader(kind); glShaderSource(shader, 1, &src, NULL); glCompileShader(shader);
    GLint ok; glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) { char log[2048]; glGetShaderInfoLog(shader, sizeof log, NULL, log); fprintf(stderr, "%s\n", log); exit(1); }
    return shader;
}
static GLuint program(const char *vs, const char *fs) {
    GLuint p = glCreateProgram(); glAttachShader(p, compile(GL_VERTEX_SHADER, vs)); glAttachShader(p, compile(GL_FRAGMENT_SHADER, fs));
    glLinkProgram(p); GLint ok; glGetProgramiv(p, GL_LINK_STATUS, &ok); assert(ok); return p;
}
static void attr(GLuint p, const char *name, int count, const float *values) {
    GLint loc = glGetAttribLocation(p, name); assert(loc >= 0);
    glEnableVertexAttribArray(loc); glVertexAttribPointer(loc, count, GL_FLOAT, GL_FALSE, 0, values);
}
static void draw(GLuint p, int preview, const float *m, unsigned char *pixels) {
    const float pos[] = {-1,-1,0,1, 1,-1,0,1, -1,1,0,1, 1,1,0,1};
    const float uv[] = {0,1, 1,1, 0,0, 1,0};
    const float flip[] = {1,0,0,0, 0,-1,0,0, 0,0,1,0, 0,1,0,1};
    glUseProgram(p); glClearColor(0,0,0,1); glClear(GL_COLOR_BUFFER_BIT);
    attr(p, "aPosition", 4, pos);
    if (preview) { attr(p, "aUv", 2, uv); glUniformMatrix4fv(glGetUniformLocation(p, "uTexMatrix"), 1, GL_FALSE, flip); }
    glUniformMatrix4fv(glGetUniformLocation(p, "uMvp"), 1, GL_FALSE, m);
    glUniform1i(glGetUniformLocation(p, "uTexture"), 0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
    assert(glGetError() == GL_NO_ERROR);
}
int main(int argc, char **argv) {
    assert(argc == 3);
    EGLDisplay d = eglGetDisplay(EGL_DEFAULT_DISPLAY); assert(eglInitialize(d,NULL,NULL));
    EGLint attrs[] = {EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
    EGLConfig cfg; EGLint n; assert(eglChooseConfig(d,attrs,&cfg,1,&n) && n);
    EGLint ca[] = {EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE}, sa[] = {EGL_WIDTH,W,EGL_HEIGHT,H,EGL_NONE};
    EGLContext ctx = eglCreateContext(d,cfg,EGL_NO_CONTEXT,ca); EGLSurface surface = eglCreatePbufferSurface(d,cfg,sa);
    assert(eglMakeCurrent(d,surface,surface,ctx)); glViewport(0,0,W,H);
    printf("GPU: %s\n", glGetString(GL_RENDERER));
    GLuint programs[] = {program(preview_vertex, preview_fragment), program(export_vertex, export_fragment)};
    unsigned char *input = malloc(BYTES), *a = malloc(BYTES), *b = malloc(BYTES), *base = malloc(BYTES), *previous = malloc(BYTES);
    for (int y=0;y<H;y++) for (int x=0;x<W;x++) {
        int i=(y*W+x)*4; input[i]=30+200*x/W; input[i+1]=30+200*y/H; input[i+2]=((x/16+y/16)%2)?220:60; input[i+3]=255;
    }
    GLuint tex; glGenTextures(1,&tex); glBindTexture(GL_TEXTURE_2D,tex);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,W,H,0,GL_RGBA,GL_UNSIGNED_BYTE,input);
    FILE *file=fopen(argv[1],"r"); assert(file); char name[80]; float m[16]; int index=0, timeline=0;
    while (fscanf(file,"%79s",name)==1) {
        for(int i=0;i<16;i++) assert(fscanf(file,"%f",m+i)==1);
        draw(programs[0],1,m,a); draw(programs[1],0,m,b);
        int lit=0, changed=0, animated=0, maxDiff=0;
        for (int i=0;i<BYTES;i++) {
            int diff=abs((int)a[i]-b[i]); if(diff>maxDiff)maxDiff=diff;
            if(i%4!=3) { if(a[i]>10)lit++; if(index && abs((int)a[i]-base[i])>4)changed++; if(timeline && abs((int)a[i]-previous[i])>4)animated++; }
        }
        assert(maxDiff<=1); assert(lit>W*H/10);
        if(!index) { memcpy(base,a,BYTES); for(int i=0;i<BYTES;i++)assert(abs((int)a[i]-input[i])<=1); }
        else if(strcmp(name,"time0")) assert(changed>W*H/10);
        if(!strncmp(name,"time",4)) { if(timeline)assert(animated>200); memcpy(previous,a,BYTES); timeline++; }
        char path[1024]; snprintf(path,sizeof path,"%s/%s.ppm",argv[2],name); FILE *image=fopen(path,"wb"); assert(image);
        fprintf(image,"P6\n%d %d\n255\n",W,H);
        for(int y=H-1;y>=0;y--)for(int x=0;x<W;x++)fwrite(a+(y*W+x)*4,1,3,image); fclose(image);
        printf("PASS %-14s lit=%d changed=%d preview/export maxDiff=%d\n",name,lit,changed,maxDiff); index++;
    }
    assert(index>=20); fclose(file); return 0;
}
