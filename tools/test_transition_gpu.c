/* Production GLSL transition pixels on a surfaceless GLES2 context. */
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "transition_cases.h"
#define W 320
#define H 180
#define PIXELS (W*H)
static char *readText(const char *path) {
 FILE *f=fopen(path,"rb"); assert(f); fseek(f,0,SEEK_END);long n=ftell(f);rewind(f);
 char *s=calloc(n+1,1);assert(fread(s,1,n,f)==(size_t)n);fclose(f);return s;
}
static GLuint compile(GLenum type,const char *s) {
 GLuint id=glCreateShader(type);glShaderSource(id,1,&s,NULL);glCompileShader(id);GLint ok;
 glGetShaderiv(id,GL_COMPILE_STATUS,&ok);if(!ok){char log[4096];glGetShaderInfoLog(id,4096,NULL,log);fprintf(stderr,"GLSL: %s\n",log);exit(1);}return id;
}
static GLuint texture(int which) {
 unsigned char *data=malloc(PIXELS*4);
 for(int y=0;y<H;y++)for(int x=0;x<W;x++) {
   int p=(y*W+x)*4;data[p]=which?25:30+x*170/W;data[p+1]=which?40+y*160/H:30;
   data[p+2]=which?60+x*120/W:35+y*180/H;data[p+3]=255;
 }
 GLuint id;glGenTextures(1,&id);glBindTexture(GL_TEXTURE_2D,id);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
 glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,W,H,0,GL_RGBA,GL_UNSIGNED_BYTE,data);free(data);return id;
}
#include "test_transition_pipeline.h"
int main(int argc,char **argv){
 assert(argc==3);setbuf(stdout,NULL);EGLDisplay d=eglGetDisplay(EGL_DEFAULT_DISPLAY);assert(eglInitialize(d,NULL,NULL));
 EGLint cfgattr[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
 EGLConfig cfg;EGLint n;assert(eglChooseConfig(d,cfgattr,&cfg,1,&n)&&n);
 EGLint ctxtattr[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE},surattr[]={EGL_WIDTH,W,EGL_HEIGHT,H,EGL_NONE};
 EGLContext ctx=eglCreateContext(d,cfg,EGL_NO_CONTEXT,ctxtattr);EGLSurface surface=eglCreatePbufferSurface(d,cfg,surattr);
 assert(eglMakeCurrent(d,surface,surface,ctx));glViewport(0,0,W,H);
 const char *vertex="attribute vec4 aFramePosition; varying vec2 vUv; void main(){gl_Position=aFramePosition;vUv=(aFramePosition.xy+1.0)*0.5;}";
 GLuint v=compile(GL_VERTEX_SHADER,vertex),a=texture(0),b=texture(1);unsigned char *pixels=malloc(PIXELS*4);
 const float pos[]={-1,-1,0,1,1,-1,0,1,-1,1,0,1,1,1,0,1};
 for(int effect=0;effect<(int)(sizeof(names)/sizeof(names[0]));effect++){
   char file[1024];snprintf(file,sizeof file,"%s/%s.frag",argv[1],names[effect]);char *src=readText(file);
   GLuint f=compile(GL_FRAGMENT_SHADER,src),p=glCreateProgram();glAttachShader(p,v);glAttachShader(p,f);glLinkProgram(p);
   GLint ok;glGetProgramiv(p,GL_LINK_STATUS,&ok);assert(ok);glUseProgram(p);
   GLint loc=glGetAttribLocation(p,"aFramePosition");assert(loc>=0);glEnableVertexAttribArray(loc);glVertexAttribPointer(loc,4,GL_FLOAT,GL_FALSE,0,pos);
   glUniform2f(glGetUniformLocation(p,"uResolution"),W,H);setDefaults(effect,p);

   glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,a);glUniform1i(glGetUniformLocation(p,"uTexA"),0);
   glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,b);glUniform1i(glGetUniformLocation(p,"uTexB"),1);
   for(int step=0;step<=4;step++){
     glUniform1f(glGetUniformLocation(p,"uProgress"),step/4.f);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
     glDrawArrays(GL_TRIANGLE_STRIP,0,4);glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,pixels);assert(glGetError()==GL_NO_ERROR);
     long lit=0, red=0,blue=0,variation=0;for(int j=0;j<PIXELS;j++){
       int k=j*4;if(pixels[k]+pixels[k+1]+pixels[k+2]>40)lit++;
       red+=pixels[k];blue+=pixels[k+2];if(j%W>=8&&(abs((int)pixels[k]-(int)pixels[k-32])+abs((int)pixels[k+1]-(int)pixels[k-31])+abs((int)pixels[k+2]-(int)pixels[k-30]))>2)variation++;
     }
     if(effect<5){assert(lit>PIXELS/4);assert(variation>W/2);}
     // Exact endpoints and linear crossfade, including both UV axes.
     if(step==0 || step==4 || effect==0) {
       float t=step/4.f;
       for(int y=0;y<H;y++)for(int x=0;x<W;x++) {
         int k=(y*W+x)*4;
         int ca[]={30+x*170/W,30,35+y*180/H};
         int cb[]={25,40+y*160/H,60+x*120/W};
         for(int c=0;c<3;c++)if(fabsf(pixels[k+c]-(ca[c]*(1-t)+cb[c]*t))>2) {
           fprintf(stderr,"Pixel mismatch %s step=%d xy=%d,%d channel=%d actual=%d expected=%.2f\n",names[effect],step,x,y,c,pixels[k+c],ca[c]*(1-t)+cb[c]*t);return 1;
         }
       }
     }
     if(effect==0&&step==2)assert(red>PIXELS*50&&blue>PIXELS*50);
     if(step==2){snprintf(file,sizeof file,"%s/%s-mid.ppm",argv[2],names[effect]);FILE *o=fopen(file,"wb");assert(o);fprintf(o,"P6\n%d %d\n255\n",W,H);for(int y=H-1;y>=0;y--)for(int x=0;x<W;x++)fwrite(pixels+(y*W+x)*4,1,3,o);fclose(o);}
     printf("PASS %-16s progress=%.2f lit=%ld varied=%ld\n",names[effect],step/4.f,lit,variation);
   }glDeleteProgram(p);glDeleteShader(f);free(src);
 }checkPipeline(argv[1],a,b);return 0;
}
