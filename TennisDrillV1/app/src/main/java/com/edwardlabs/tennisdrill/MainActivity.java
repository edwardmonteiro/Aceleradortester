package com.edwardlabs.tennisdrill;

import android.app.Activity;
import android.graphics.*;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.hardware.*;
import android.view.*;
import android.widget.*;
import android.content.*;
import java.util.*;

public class MainActivity extends Activity implements SensorEventListener {
    static final int BG=Color.rgb(10,13,12), SURFACE=Color.rgb(21,25,23), TEXT=Color.rgb(244,247,245), MUTED=Color.rgb(166,176,170), ACCENT=Color.rgb(216,255,87), RED=Color.rgb(255,120,120);
    SensorManager sm; Sensor lin, gyro, rot;
    TextView status, live, metrics, verdict; Button calibrate, arm; ProgressBar progress; AnalysisView analysis;
    final Handler h=new Handler(Looper.getMainLooper());
    boolean calibrating=false, calibrated=false, armed=false, recording=false;
    long calStart, recordStart, quietStart;
    final float[] aBias=new float[3], gBias=new float[3], lastA=new float[3], lastG=new float[3], lastR=new float[9], baseR=new float[9];
    int calN=0; final ArrayList<Sample> samples=new ArrayList<>();

    @Override public void onCreate(Bundle b){ super.onCreate(b); getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG); buildUi(); sm=(SensorManager)getSystemService(SENSOR_SERVICE); lin=sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION); gyro=sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE); rot=sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR); updateAvailability(); }
    @Override protected void onResume(){ super.onResume(); if(lin!=null) sm.registerListener(this,lin,SensorManager.SENSOR_DELAY_FASTEST); if(gyro!=null) sm.registerListener(this,gyro,SensorManager.SENSOR_DELAY_FASTEST); if(rot!=null) sm.registerListener(this,rot,SensorManager.SENSOR_DELAY_FASTEST); }
    @Override protected void onPause(){ sm.unregisterListener(this); super.onPause(); }

    void buildUi(){
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG); scroll.setFitsSystemWindows(true);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20),dp(18),dp(20),dp(36)); scroll.addView(root,new ScrollView.LayoutParams(-1,-2));
        root.addView(tv("TENNIS DRILL  V1",13,MUTED,true)); root.addView(tv("Your phone is the racket.",30,TEXT,true)); root.addView(tv("100% local • no camera • no internet",14,MUTED,false));
        TextView safety=tv("Use a secure grip or wrist tether. Clear the area before swinging.",12,MUTED,false); safety.setPadding(0,dp(8),0,0); root.addView(safety);
        root.addView(space(18)); status=tv("Calibrate before your first swing.",18,TEXT,true); root.addView(card(status));
        live=tv("Checking sensors…",13,MUTED,false); root.addView(live);
        root.addView(space(20)); root.addView(tv("1  CALIBRATE",13,MUTED,true)); root.addView(tv("Hold the phone upright and still. Screen toward you, back of phone toward the net. Calibration takes 3 seconds.",14,MUTED,false));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(1000); progress.setVisibility(View.GONE); root.addView(progress,new LinearLayout.LayoutParams(-1,dp(8))); root.addView(space(10));
        calibrate=button("CALIBRATE 3 SEC",false); calibrate.setOnClickListener(v->beginCalibration()); root.addView(calibrate);
        root.addView(space(22)); root.addView(tv("2  SWING",13,MUTED,true)); arm=button("ARM SWING",true); arm.setEnabled(false); arm.setAlpha(.45f); arm.setOnClickListener(v->{ if(!armed) armSwing(); else cancelSwing(); }); root.addView(arm);
        root.addView(space(24)); root.addView(tv("RESULT",13,MUTED,true)); verdict=tv("No swing recorded yet.",28,TEXT,true); root.addView(verdict); metrics=tv("",16,TEXT,false); metrics.setPadding(0,dp(8),0,0); root.addView(metrics);
        root.addView(space(18)); analysis=new AnalysisView(this); analysis.setBackgroundColor(SURFACE); root.addView(analysis,new LinearLayout.LayoutParams(-1,dp(600)));
        TextView note=tv("IN/OUT and ball speed are projections from phone motion, not measurements of a real ball.",12,MUTED,false); note.setPadding(0,dp(10),0,0); root.addView(note);
        setContentView(scroll);
    }

    void updateAvailability(){ boolean ok=lin!=null&&gyro!=null&&rot!=null; live.setText("Sensors • linear accel "+yes(lin!=null)+" • gyro "+yes(gyro!=null)+" • rotation "+yes(rot!=null)); if(!ok){ live.setTextColor(RED); status.setText("This phone is missing a required motion sensor."); } }
    String yes(boolean v){return v?"✓":"—";}
    void beginCalibration(){ if(lin==null||gyro==null||rot==null)return; calibrating=true; calibrated=false; armed=false; recording=false; calStart=System.nanoTime(); calN=0; Arrays.fill(aBias,0); Arrays.fill(gBias,0); progress.setProgress(0); progress.setVisibility(View.VISIBLE); arm.setEnabled(false); arm.setAlpha(.45f); status.setText("Keep the phone completely still…"); }
    void armSwing(){ armed=true; recording=false; samples.clear(); arm.setText("CANCEL"); status.setText("Armed. Hold ready, then swing naturally."); }
    void cancelSwing(){ armed=false; recording=false; samples.clear(); arm.setText("ARM SWING"); status.setText("Capture cancelled."); }

    @Override public void onSensorChanged(SensorEvent e){
        if(e.sensor.getType()==Sensor.TYPE_LINEAR_ACCELERATION){System.arraycopy(e.values,0,lastA,0,3);} else if(e.sensor.getType()==Sensor.TYPE_GYROSCOPE){System.arraycopy(e.values,0,lastG,0,3);} else if(e.sensor.getType()==Sensor.TYPE_ROTATION_VECTOR){SensorManager.getRotationMatrixFromVector(lastR,e.values);} else return;
        long now=e.timestamp;
        if(calibrating){
            for(int i=0;i<3;i++){aBias[i]+=lastA[i];gBias[i]+=lastG[i];} calN++;
            float p=Math.min(1f,(System.nanoTime()-calStart)/3_000_000_000f); progress.setProgress((int)(1000*p));
            if(p>=1f){ for(int i=0;i<3;i++){aBias[i]/=Math.max(1,calN);gBias[i]/=Math.max(1,calN);} System.arraycopy(lastR,0,baseR,0,9); calibrating=false; calibrated=true; progress.setVisibility(View.GONE); arm.setEnabled(true); arm.setAlpha(1f); status.setText("Calibrated. Ready for a swing."); }
            return;
        }
        if(!armed||!calibrated)return;
        double am=mag(lastA[0]-aBias[0],lastA[1]-aBias[1],lastA[2]-aBias[2]), gm=mag(lastG[0]-gBias[0],lastG[1]-gBias[1],lastG[2]-gBias[2]);
        live.setText(String.format(Locale.US,"Motion • %.1f m/s² • %.1f rad/s",am,gm));
        if(!recording){ if(am>2.4||gm>1.25){ recording=true; recordStart=now; quietStart=0; status.setText("Recording swing…"); arm.setText("RECORDING"); arm.setEnabled(false); addSample(now); } }
        else { addSample(now); long elapsed=now-recordStart; boolean quiet=am<1.0&&gm<0.55; if(quiet&&elapsed>450_000_000L){ if(quietStart==0)quietStart=now; if(now-quietStart>320_000_000L)finishSwing(); } else quietStart=0; if(elapsed>2_800_000_000L)finishSwing(); }
    }
    void addSample(long t){
        float[] rel=mulMat(transpose(baseR),lastR); float ax=lastA[0]-aBias[0], ay=lastA[1]-aBias[1], az=lastA[2]-aBias[2]; float[] ar=mulVec(rel,new float[]{ax,ay,az}); float[] gr=mulVec(rel,new float[]{lastG[0]-gBias[0],lastG[1]-gBias[1],lastG[2]-gBias[2]}); float[] face=mulVec(rel,new float[]{0,0,-1});
        samples.add(new Sample(t,new V(ar[0],ar[1],-ar[2]),new V(gr[0],gr[1],-gr[2]),new V(face[0],face[1],-face[2]).norm()));
    }
    void finishSwing(){ armed=false; recording=false; arm.setText("ARM SWING"); arm.setEnabled(true); if(samples.size()<12){status.setText("Swing too short. Try again.");return;} Result r=analyze(samples); if(r==null){status.setText("Could not reconstruct this swing. Try again.");return;} status.setText("Swing analyzed locally."); verdict.setText(r.ball.result); verdict.setTextColor("IN ✓".equals(r.ball.result)?ACCENT:RED); metrics.setText(String.format(Locale.US,"Swing speed      %.1f km/h\nImpact speed     %.1f km/h\nProjected ball   %.1f km/h\nFace angle       %+.1f°\nLaunch angle     %+.1f°\nSpin proxy       %.0f rpm\nLanding          x %+.2f m • %.2f m beyond net",r.maxSpeed*3.6,r.impactSpeed*3.6,r.ball.speed*3.6,r.facePitch,r.pathPitch,r.spin, r.ball.x, r.ball.z-11.885)); analysis.setResult(r); }
    @Override public void onAccuracyChanged(Sensor s,int a){}

    Result analyze(ArrayList<Sample> in){ int n=in.size(); if(n<12)return null; double total=(in.get(n-1).t-in.get(0).t)/1e9; if(total<.12)return null; V[] vel=new V[n]; V[] pos=new V[n]; vel[0]=new V(); pos[0]=new V(); double maxA=0,maxG=0,maxV=0; for(int i=1;i<n;i++){double dt=clamp((in.get(i).t-in.get(i-1).t)/1e9,.0005,.05); V aa=in.get(i-1).a.add(in.get(i).a).mul(.5*dt); vel[i]=vel[i-1].add(aa); maxA=Math.max(maxA,in.get(i).a.mag()); maxG=Math.max(maxG,in.get(i).g.mag());}
        V drift=vel[n-1]; for(int i=0;i<n;i++){double ti=(in.get(i).t-in.get(0).t)/1e9; vel[i]=vel[i].sub(drift.mul(ti/total)); maxV=Math.max(maxV,vel[i].mag());}
        for(int i=1;i<n;i++){double dt=clamp((in.get(i).t-in.get(i-1).t)/1e9,.0005,.05); pos[i]=pos[i-1].add(vel[i-1].add(vel[i]).mul(.5*dt));}
        int first=Math.max(1,(int)(n*.18)), last=Math.min(n-1,(int)(n*.90)), impact=first; double best=-1; for(int i=first;i<=last;i++){double sc=.25*in.get(i).a.mag()/Math.max(maxA,1e-6)+.30*in.get(i).g.mag()/Math.max(maxG,1e-6)+.45*vel[i].mag()/Math.max(maxV,1e-6); if(sc>best){best=sc;impact=i;}}
        V iv=new V(); int c=0; for(int i=Math.max(0,impact-2);i<=Math.min(n-1,impact+2);i++){iv=iv.add(vel[i]);c++;} iv=iv.mul(1.0/c); V face=in.get(impact).face.norm(); double pathPitch=Math.toDegrees(Math.atan2(iv.y,Math.sqrt(iv.x*iv.x+iv.z*iv.z))); double facePitch=Math.toDegrees(Math.atan2(face.y,Math.sqrt(face.x*face.x+face.z*face.z))); double spin=clamp(Math.abs(pathPitch-facePitch)*65+in.get(impact).g.mag()*110,0,4200); Ball ball=project(iv,face,spin,pos[impact]); return new Result(maxV,iv.mag(),pathPitch,facePitch,spin,impact,pos,vel,ball); }
    Ball project(V swing,V face,double spin,V impactPos){ V sd=swing.norm(); V dir=face.mul(.68).add(sd.mul(.32)).norm(); if(dir.z<.05)return new Ball("NO FORWARD BALL",0,0,0,new ArrayList<>()); double speed=clamp(swing.mag()*7+3,5,55); V v=dir.mul(speed); V p=new V(clamp(impactPos.x,-1.5,1.5),clamp(1+impactPos.y,.45,2.3),clamp(impactPos.z,-.8,1.5)); ArrayList<V> pts=new ArrayList<>(); pts.add(p); double netH=Double.NaN, dt=.005, extra=clamp(spin/4200,0,1)*4; V prev=p; for(int step=0;step<1600;step++){double sp=v.mag(); V drag=sp>1e-6?v.norm().mul(-.012*sp*sp):new V(); V acc=drag.add(new V(0,-9.80665-extra,0)); v=v.add(acc.mul(dt)); p=p.add(v.mul(dt)); if(step%4==0)pts.add(p); if(Double.isNaN(netH)&&prev.z<11.885&&p.z>=11.885){double f=(11.885-prev.z)/Math.max(1e-9,p.z-prev.z);netH=prev.y+(p.y-prev.y)*f;} if(p.y<=0&&step>4){double f=prev.y/Math.max(1e-9,prev.y-p.y);double x=prev.x+(p.x-prev.x)*f,z=prev.z+(p.z-prev.z)*f;boolean crossed=z>11.885, cleared=!Double.isNaN(netH)&&netH>.914, inside=z>=11.885&&z<=23.77&&Math.abs(x)<=4.115;String res=crossed&&!cleared?"NET":inside&&cleared?"IN ✓":"OUT";pts.add(new V(x,0,z));return new Ball(res,speed,x,z,pts);} prev=p;} return new Ball("OUT",speed,p.x,p.z,pts); }

    class AnalysisView extends View { Paint p=new Paint(1); Result r; AnalysisView(Context c){super(c);p.setStrokeWidth(dp(2));} void setResult(Result rr){r=rr;invalidate();} @Override protected void onDraw(Canvas c){super.onDraw(c); int w=getWidth(); p.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD)); p.setTextSize(dp(13));p.setColor(MUTED);c.drawText("SWING PATH — TOP",dp(16),dp(28),p); RectF top=new RectF(dp(16),dp(44),w-dp(16),dp(240)); box(c,top); c.drawText("PROJECTED COURT",dp(16),dp(286),p); RectF court=new RectF(dp(30),dp(306),w-dp(30),getHeight()-dp(24)); court(c,court); if(r==null)return; path(c,top,r.pos); ball(c,court,r.ball.pts); }
        void box(Canvas c,RectF q){p.setStyle(Paint.Style.STROKE);p.setColor(Color.rgb(70,78,74));c.drawRect(q,p);p.setStyle(Paint.Style.FILL);}
        void path(Canvas c,RectF q,V[] pts){ if(pts.length<2)return; double minX=1e9,maxX=-1e9,minZ=1e9,maxZ=-1e9;for(V v:pts){minX=Math.min(minX,v.x);maxX=Math.max(maxX,v.x);minZ=Math.min(minZ,v.z);maxZ=Math.max(maxZ,v.z);} double sx=Math.max(.25,maxX-minX),sz=Math.max(.25,maxZ-minZ); Path path=new Path();for(int i=0;i<pts.length;i++){float x=(float)(q.left+20+(pts[i].x-minX)/sx*(q.width()-40));float y=(float)(q.bottom-20-(pts[i].z-minZ)/sz*(q.height()-40));if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(3));p.setColor(ACCENT);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);}
        void court(Canvas c,RectF q){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setColor(Color.rgb(125,138,131));c.drawRect(q,p);float netY=q.bottom-q.height()*.5f;c.drawLine(q.left,netY,q.right,netY,p);float service1=q.bottom-q.height()*(6.40f/23.77f),service2=q.bottom-q.height()*(17.37f/23.77f);c.drawLine(q.left,service1,q.right,service1,p);c.drawLine(q.left,service2,q.right,service2,p);c.drawLine(q.centerX(),service1,q.centerX(),service2,p);p.setStyle(Paint.Style.FILL);}
        void ball(Canvas c,RectF q,ArrayList<V> pts){if(pts.size()<2)return;Path path=new Path();for(int i=0;i<pts.size();i++){V v=pts.get(i);float x=(float)(q.centerX()+v.x/4.115*(q.width()/2));float y=(float)(q.bottom-v.z/23.77*q.height());if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(3));p.setColor(ACCENT);c.drawPath(path,p);V l=pts.get(pts.size()-1);float x=(float)(q.centerX()+l.x/4.115*(q.width()/2)),y=(float)(q.bottom-l.z/23.77*q.height());p.setStyle(Paint.Style.FILL);c.drawCircle(x,y,dp(7),p);}
    }

    static class V{double x,y,z;V(){this(0,0,0);}V(double X,double Y,double Z){x=X;y=Y;z=Z;}V add(V o){return new V(x+o.x,y+o.y,z+o.z);}V sub(V o){return new V(x-o.x,y-o.y,z-o.z);}V mul(double s){return new V(x*s,y*s,z*s);}double mag(){return Math.sqrt(x*x+y*y+z*z);}V norm(){double m=mag();return m<1e-9?new V():mul(1/m);}}
    static class Sample{long t;V a,g,face;Sample(long T,V A,V G,V F){t=T;a=A;g=G;face=F;}}
    static class Ball{String result;double speed,x,z;ArrayList<V> pts;Ball(String R,double S,double X,double Z,ArrayList<V>P){result=R;speed=S;x=X;z=Z;pts=P;}}
    static class Result{double maxSpeed,impactSpeed,pathPitch,facePitch,spin;int impact;V[] pos,vel;Ball ball;Result(double a,double b,double c,double d,double e,int f,V[]g,V[]h,Ball i){maxSpeed=a;impactSpeed=b;pathPitch=c;facePitch=d;spin=e;impact=f;pos=g;vel=h;ball=i;}}

    float[] transpose(float[] a){return new float[]{a[0],a[3],a[6],a[1],a[4],a[7],a[2],a[5],a[8]};}
    float[] mulMat(float[] a,float[] b){float[] o=new float[9];for(int r=0;r<3;r++)for(int c=0;c<3;c++)o[r*3+c]=a[r*3]*b[c]+a[r*3+1]*b[3+c]+a[r*3+2]*b[6+c];return o;}
    float[] mulVec(float[] m,float[] v){return new float[]{m[0]*v[0]+m[1]*v[1]+m[2]*v[2],m[3]*v[0]+m[4]*v[1]+m[5]*v[2],m[6]*v[0]+m[7]*v[1]+m[8]*v[2]};}
    double mag(double x,double y,double z){return Math.sqrt(x*x+y*y+z*z);} static double clamp(double v,double a,double b){return Math.max(a,Math.min(b,v));}
    TextView tv(String s,float sp,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setLineSpacing(0,1.12f);if(bold)v.setTypeface(v.getTypeface(),Typeface.BOLD);return v;}
    View card(View child){LinearLayout c=new LinearLayout(this);c.setPadding(dp(16),dp(14),dp(16),dp(14));c.setBackgroundColor(SURFACE);c.addView(child);return c;}
    Button button(String s,boolean primary){Button b=new Button(this);b.setText(s);b.setTextSize(15);b.setTypeface(b.getTypeface(),Typeface.BOLD);b.setMinHeight(dp(56));b.setTextColor(primary?BG:TEXT); android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();g.setCornerRadius(dp(12));g.setColor(primary?ACCENT:SURFACE);g.setStroke(dp(1),primary?ACCENT:Color.rgb(70,78,74));b.setBackground(g);return b;}
    Space space(int d){Space s=new Space(this);s.setLayoutParams(new LinearLayout.LayoutParams(1,dp(d)));return s;} int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
}
