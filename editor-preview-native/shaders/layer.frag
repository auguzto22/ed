precision highp float;
uniform sampler2D uTexture;
uniform vec4 uCrop;
uniform vec4 uAdjust;
uniform vec4 uColor;
uniform vec4 uPreset;
uniform vec4 uGrade;
uniform vec4 uChroma;
uniform vec3 uKeyEdge;
uniform float uOpacity;
uniform vec2 uFade;
varying vec2 vUv;
vec3 hue(vec3 c, float h) {
    float a = h * 6.2831853;
    vec3 k = vec3(.57735);
    return c*cos(a) + cross(k,c)*sin(a) + k*dot(k,c)*(1.-cos(a));
}
void main() {
    if (vUv.x<uCrop.x||vUv.x>uCrop.z||vUv.y<uCrop.y||vUv.y>uCrop.w) discard;
    vec4 q=texture2D(uTexture,vUv);
    vec3 c=q.rgb*uPreset.rgb;
    if(uPreset.w>1.5)c=1.-c;
    float l=dot(c,vec3(.2126,.7152,.0722));
    if(uPreset.w>.5&&uPreset.w<1.5)c=vec3(l);
    c+=uAdjust.x+uAdjust.w+uGrade.x*.12;
    c=(c-.5)*(1.+uAdjust.y)+.5;
    l=dot(c,vec3(.2126,.7152,.0722));
    c=mix(vec3(l),c,1.+uAdjust.z);
    c=hue(c,uColor.x)*uColor.yzw;
    c=mix(c,c*c*(3.-2.*c),uGrade.z*.25);
    float v=smoothstep(.8,.2,length(vUv-.5));
    c*=mix(1.,v,uGrade.w);
    float alpha=q.a*uOpacity;
    if(uChroma.w>0.) {
        vec3 keyRgb=pow(max(q.rgb,vec3(0.)),vec3(1./2.2));
        float threshold=max(0.,uChroma.w+uKeyEdge.z);
        float matte=smoothstep(threshold,threshold+uKeyEdge.x,distance(keyRgb,uChroma.xyz));
        alpha*=matte;
        vec3 dominance=max(uChroma.xyz-max(uChroma.yzx,uChroma.zxy),vec3(0.));
        dominance/=max(.0001,max(dominance.r,max(dominance.g,dominance.b)));
        c=mix(c,min(c,vec3(dot(c,vec3(.2126,.7152,.0722)))),dominance*uKeyEdge.y*(1.-matte));
    }
    gl_FragColor=vec4(clamp(c*uFade.x+uFade.y,0.,1.),clamp(alpha,0.,1.));
}
