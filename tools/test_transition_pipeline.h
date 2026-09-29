/* Compare production preview vertex/draw shaders through the two-input FBO chain
 * against independently rendered clips. Exercises crop, rotation, scale, position,
 * transparency and Y orientation. Included by test_transition_gpu.c. */
static const float identity[]={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
static const float flipY[]={1,0,0,0,0,-1,0,0,0,0,1,0,0,1,0,1};
static const float previewQuad[]={-1,-1,0,1,1,-1,1,1,-1,1,0,0,1,1,1,0};
static GLuint pipelineProgram(const char *fragment) {
 GLuint v=compile(GL_VERTEX_SHADER,production_vertex),f=compile(GL_FRAGMENT_SHADER,fragment),p=glCreateProgram();
 glAttachShader(p,v);glAttachShader(p,f);glLinkProgram(p);GLint ok;glGetProgramiv(p,GL_LINK_STATUS,&ok);assert(ok);
 glDeleteShader(v);glDeleteShader(f);return p;
}
static void pipelineQuad(GLuint p,const float *matrix,const float *uv) {
 glUseProgram(p);GLint pos=glGetAttribLocation(p,"aPosition"),tex=glGetAttribLocation(p,"aUv");
 glEnableVertexAttribArray(pos);glVertexAttribPointer(pos,2,GL_FLOAT,GL_FALSE,4*sizeof(float),previewQuad);
 glEnableVertexAttribArray(tex);glVertexAttribPointer(tex,2,GL_FLOAT,GL_FALSE,4*sizeof(float),previewQuad+2);
 glUniformMatrix4fv(glGetUniformLocation(p,"uMvp"),1,GL_FALSE,matrix);
 glUniformMatrix4fv(glGetUniformLocation(p,"uTexMatrix"),1,GL_FALSE,uv);
}
static GLuint pipelineTarget(GLuint *tex) {
 glGenTextures(1,tex);glBindTexture(GL_TEXTURE_2D,*tex);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
 glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,W,H,0,GL_RGBA,GL_UNSIGNED_BYTE,NULL);
 GLuint f;glGenFramebuffers(1,&f);glBindFramebuffer(GL_FRAMEBUFFER,f);
 glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,*tex,0);
 assert(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE);return f;
}
static void pipelineLayer(GLuint p,GLuint tex,int which) {
 float m[16];memcpy(m,identity,sizeof m);float angle=which?-.4f:.25f,scale=which?.85f:.75f;
 m[0]=cosf(angle)*scale;m[1]=sinf(angle)*scale;m[4]=-sinf(angle)*scale;m[5]=cosf(angle)*scale;
 m[12]=which?.15f:-.15f;m[13]=which?-.1f:.1f;
 pipelineQuad(p,m,identity);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,tex);glUniform1i(glGetUniformLocation(p,"uTexture"),0);
 glUniform4f(glGetUniformLocation(p,"uCrop"),.1f,.15f,.9f,.85f);
 glUniform4f(glGetUniformLocation(p,"uAdjust"),0,0,0,0);glUniform4f(glGetUniformLocation(p,"uColor"),0,1,1,1);
 glUniform4f(glGetUniformLocation(p,"uPreset"),1,1,1,0);glUniform4f(glGetUniformLocation(p,"uGrade"),0,0,0,0);
 glUniform1f(glGetUniformLocation(p,"uOpacity"),which?.8f:.45f);
 glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
 glDrawArrays(GL_TRIANGLE_STRIP,0,4);
}
static void checkPipeline(const char *shaderDir,GLuint a,GLuint b) {
 char path[1024];snprintf(path,sizeof path,"%s/cross_dissolve.frag",shaderDir);char *src=readText(path);
 GLuint draw=pipelineProgram(production_draw),cross=pipelineProgram(src),overlay=pipelineProgram(production_overlay);free(src);
 GLuint tex[3],fbo[3];for(int i=0;i<3;i++)fbo[i]=pipelineTarget(&tex[i]);
 unsigned char *refs[2]={malloc(PIXELS*4),malloc(PIXELS*4)},*actual=malloc(PIXELS*4);
 glViewport(0,0,W,H);glDisable(GL_SCISSOR_TEST);glDisable(GL_DEPTH_TEST);
 for(int i=0;i<2;i++) {
   glBindFramebuffer(GL_FRAMEBUFFER,0);glClearColor(.08f,.12f,.18f,1);glClear(GL_COLOR_BUFFER_BIT);
   pipelineLayer(draw,i?b:a,i);glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,refs[i]);
   glBindFramebuffer(GL_FRAMEBUFFER,fbo[i]);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT);pipelineLayer(draw,i?b:a,i);
 }
 for(int step=0;step<=4;step++) {
   float t=step/4.f;glBindFramebuffer(GL_FRAMEBUFFER,fbo[2]);glDisable(GL_BLEND);pipelineQuad(cross,identity,flipY);
   glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,tex[0]);glUniform1i(glGetUniformLocation(cross,"uTexA"),0);
   glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,tex[1]);glUniform1i(glGetUniformLocation(cross,"uTexB"),1);
   glUniform1f(glGetUniformLocation(cross,"uProgress"),t);glUniform1f(glGetUniformLocation(cross,"p_softness"),0);glUniform1f(glGetUniformLocation(cross,"p_mode"),0);
   glDrawArrays(GL_TRIANGLE_STRIP,0,4);
   glBindFramebuffer(GL_FRAMEBUFFER,0);glClearColor(.08f,.12f,.18f,1);glClear(GL_COLOR_BUFFER_BIT);
   pipelineQuad(overlay,identity,flipY);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,tex[2]);glUniform1i(glGetUniformLocation(overlay,"uTexture"),0);
   glEnable(GL_BLEND);glBlendFunc(GL_ONE,GL_ONE_MINUS_SRC_ALPHA);glDrawArrays(GL_TRIANGLE_STRIP,0,4);
   glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,actual);assert(glGetError()==GL_NO_ERROR);
   for(int k=0;k<PIXELS*4;k++) {
     float expected=refs[0][k]*(1-t)+refs[1][k]*t;
     if(fabsf(actual[k]-expected)>3) {fprintf(stderr,"Pipeline mismatch t=%f byte=%d expected=%f actual=%u\n",t,k,expected,actual[k]);exit(1);}
   }
   printf("PASS preview FBO pipeline: transforms, crop, opacity, orientation progress=%.2f\n",t);
 }
 for(int i=0;i<2;i++)free(refs[i]);free(actual);glDeleteFramebuffers(3,fbo);glDeleteTextures(3,tex);
 glDeleteProgram(draw);glDeleteProgram(cross);glDeleteProgram(overlay);
}
