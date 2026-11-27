package com.wygtv.tv;

import android.content.res.Resources;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;

/** Steampunk artwork scaled to cover the screen, with optional scrims that keep text readable. */
public final class SteampunkBackdrop extends Drawable {
 private static final SparseArray<Bitmap> CACHE=new SparseArray<>(),FROSTED=new SparseArray<>();
 private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
 private final Bitmap art;
 private final boolean homeScrim;

 public SteampunkBackdrop(Resources res,int drawableId,boolean homeScrim){
  Bitmap cached=CACHE.get(drawableId);
  if(cached==null){cached=BitmapFactory.decodeResource(res,drawableId);CACHE.put(drawableId,cached);}
  if(Theme.glass&&cached!=null){Bitmap f=FROSTED.get(drawableId);if(f==null){f=frost(cached);FROSTED.put(drawableId,f);}cached=f;}
  art=cached;this.homeScrim=homeScrim;
 }

 /** Glass effect: a soft blur of the artwork (shrunk in steps, then stretched back with filtering when drawn). */
 static Bitmap frost(Bitmap src){
  Bitmap b=src;int w=src.getWidth(),h=src.getHeight();
  for(int i=0;i<3&&w>48;i++){w=Math.max(1,w/3);h=Math.max(1,h/3);b=Bitmap.createScaledBitmap(b,w,h,true);}
  return b;
 }

 @Override public void draw(Canvas c){
  Rect b=getBounds();float w=b.width(),h=b.height();if(w<=0||h<=0)return;
  c.drawColor(Theme.BG);
  if(art!=null){
   float scale=Math.max(w/art.getWidth(),h/art.getHeight());float dw=art.getWidth()*scale,dh=art.getHeight()*scale;
   float left=b.left+(w-dw)/2f,top=b.top+(h-dh)/2f;
   paint.setShader(null);c.drawBitmap(art,null,new RectF(left,top,left+dw,top+dh),paint);
  }
  if(!Theme.steampunk()){
   // Other themes wash the artwork in their own colour so it reads as texture.
   int top=Theme.glass?0xA0:0xC8,bottom=Theme.glass?0xC8:0xE0;
   paint.setShader(new LinearGradient(0,b.top,0,b.bottom,new int[]{Theme.alpha(Theme.PANEL,top),Theme.alpha(Theme.BG,bottom)},null,Shader.TileMode.CLAMP));c.drawRect(b,paint);paint.setShader(null);
  }
  if(homeScrim){
   // Soft darkening behind the centre tiles and along the bottom plate so cream text stays legible.
   paint.setShader(new RadialGradient(b.centerX(),b.top+h*.52f,w*.42f,new int[]{Theme.alpha(Theme.BG,0x8C),Theme.alpha(Theme.BG,0x40),Theme.alpha(Theme.BG,0x00)},new float[]{0f,.6f,1f},Shader.TileMode.CLAMP));c.drawRect(b,paint);
   paint.setShader(new LinearGradient(0,b.top+h*.78f,0,b.bottom,new int[]{Theme.alpha(Theme.BG,0x00),Theme.alpha(Theme.BG,0xB3)},null,Shader.TileMode.CLAMP));c.drawRect(b,paint);
   paint.setShader(new LinearGradient(0,b.top,0,b.top+h*.16f,new int[]{Theme.alpha(Theme.BG,0x99),Theme.alpha(Theme.BG,0x00)},null,Shader.TileMode.CLAMP));c.drawRect(b,paint);
   paint.setShader(null);
  }
 }

 @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
 @Override public void setColorFilter(ColorFilter cf){paint.setColorFilter(cf);invalidateSelf();}
 @Override public int getOpacity(){return PixelFormat.OPAQUE;}
}
