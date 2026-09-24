package com.edwardlabs.tennisdrill;

import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.content.*;
import java.util.*;

public class MainActivity extends Activity implements SensorEventListener {
    static final int BG=Color.rgb(9,12,11), SURFACE=Color.rgb(20,24,22), TEXT=Color.rgb(244,247,245),
        MUTED=Color.rgb(160,171,165), ACCENT=Color.rgb(216,255,87), RED=Color.rgb(255,116,116), LINE=Color.rgb(64,73,68);

    enum Stage { BASELINE, LEARN, READY, PRACTICE, COMPLETE }

    SensorManager sm; Sensor lin, gyro, rot;
    TextView stageLabel, title, instruction, live, repCounter, metrics, coach, session;
    Button primary;
    ProgressBar progress;
    MotionView motionView;

    Stage stage=Stage.BASELINE;
    boolean baselineRunning=false, seriesArmed=false, recording=false;
    int referenceCount=0, practiceCount=0;
    long baselineStart=0, recordStart=0, quietStart=0;
    int baselineN=0;

    final float[] aBias=new float[3], gBias=new float[3], lastA=new float[3], lastG=new float[3],
        lastR=new float[9], baseR=new float[9];
    final ArrayList<Sample> current=new ArrayList<>();
    final ArrayList<Metrics> references=new ArrayList<>();
    final ArrayList<Metrics> practice=new ArrayList<>();
    Metrics referenceProfile=null, latest=null;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        sm=(SensorManager)getSystemService(SENSOR_SERVICE);
        lin=sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        gyro=sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        rot=sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        updateSensorStatus();
    }

    @Override protected void onResume(){
        super.onResume();
        registerSensorSafely(lin);
        registerSensorSafely(gyro);
        registerSensorSafely(rot);
    }

    @Override protected void onPause(){
        if(sm!=null) sm.unregisterListener(this);
        super.onPause();
    }

    void registerSensorSafely(Sensor sensor){
        if(sensor==null) return;
        try{
            boolean ok=sm.registerListener(this,sensor,5000);
            if(!ok) sm.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME);
        }catch(Exception ex){
            try{ sm.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME); }catch(Exception ignored){}
        }
    }

    void buildUi(){
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(16),dp(20),dp(36));
        scroll.addView(root,new ScrollView.LayoutParams(-1,-2));

        root.addView(tv("TENNIS DRILL  V3",13,MUTED,true));
        root.addView(tv("Shadow Forehand Coach",30,TEXT,true));
        root.addView(tv("Phone only • 100% local • hands-free during sets",14,MUTED,false));

        TextView safety=tv("Hold the phone securely with the screen facing you. Clear the area before swinging.",12,MUTED,false);
        safety.setPadding(0,dp(8),0,0); root.addView(safety);

        root.addView(space(18));
        stageLabel=tv("STEP 1 OF 3",12,MUTED,true); root.addView(stageLabel);
        title=tv("Calibrate sensors",24,TEXT,true); root.addView(title);
        instruction=tv("Hold your normal forehand ready position. Keep the phone still for 2.5 seconds.",14,MUTED,false);
        instruction.setPadding(0,dp(6),0,dp(12)); root.addView(instruction);

        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000); progress.setVisibility(View.GONE);
        root.addView(progress,new LinearLayout.LayoutParams(-1,dp(8)));
        root.addView(space(10));

        primary=button("START CALIBRATION",true);
        primary.setOnClickListener(v->onPrimary());
        root.addView(primary);

        root.addView(space(18));
        live=tv("Checking motion sensors…",13,MUTED,false); root.addView(live);

        root.addView(space(20));
        repCounter=tv("0 / 3 REFERENCE SWINGS",18,TEXT,true); root.addView(card(repCounter));
        root.addView(space(12));

        motionView=new MotionView(this);
        motionView.setBackgroundColor(SURFACE);
        root.addView(motionView,new LinearLayout.LayoutParams(-1,dp(260)));

        root.addView(space(18));
        root.addView(tv("LATEST SWING",12,MUTED,true));
        metrics=tv("No swing recorded yet.",17,TEXT,false); metrics.setPadding(0,dp(6),0,0); root.addView(metrics);

        root.addView(space(18));
        root.addView(tv("ONE THING TO FIX",12,MUTED,true));
        coach=tv("Complete calibration first.",19,ACCENT,true); coach.setPadding(0,dp(6),0,0); root.addView(coach);

        root.addView(space(20));
        root.addView(tv("SESSION",12,MUTED,true));
        session=tv("Your 3 slow forehands become the reference for the 10-rep set.",14,MUTED,false); root.addView(session);

        setContentView(scroll);
    }

    void onPrimary(){
        if(stage==Stage.BASELINE) beginBaseline();
        else if(stage==Stage.LEARN) startReferenceSet();
        else if(stage==Stage.READY) startPracticeSet();
        else if(stage==Stage.COMPLETE) resetPractice();
    }

    void beginBaseline(){
        if(lin==null||gyro==null||rot==null){
            title.setText("Required sensors unavailable");
            coach.setText("This phone needs linear acceleration, gyroscope and rotation vector sensors.");
            coach.setTextColor(RED);
            return;
        }
        baselineRunning=true; baselineStart=System.nanoTime(); baselineN=0;
        Arrays.fill(aBias,0); Arrays.fill(gBias,0);
        progress.setProgress(0); progress.setVisibility(View.VISIBLE);
        primary.setEnabled(false); primary.setAlpha(.5f);
        title.setText("Stay still…");
        instruction.setText("Keep the screen facing you in your normal ready position.");
    }

    void finishBaseline(){
        for(int i=0;i<3;i++){aBias[i]/=Math.max(1,baselineN);gBias[i]/=Math.max(1,baselineN);}
        System.arraycopy(lastR,0,baseR,0,9);
        baselineRunning=false; stage=Stage.LEARN;
        progress.setVisibility(View.GONE);
        stageLabel.setText("STEP 2 OF 3");
        title.setText("Teach me your forehand");
        instruction.setText("Do 3 complete slow forehands. Start from ready, take back, swing through, then finish. No taps between repetitions.");
        primary.setText("START 3 SLOW FOREHANDS");
        primary.setEnabled(true); primary.setAlpha(1f);
        repCounter.setText("0 / 3 REFERENCE SWINGS");
        coach.setText("Focus on a smooth, complete motion.");
    }

    void startReferenceSet(){
        references.clear(); referenceCount=0; practiceCount=0; latest=null;
        current.clear(); seriesArmed=true; recording=false; stage=Stage.LEARN;
        primary.setEnabled(false); primary.setAlpha(.45f); primary.setText("LISTENING…");
        title.setText("3 slow forehands");
        instruction.setText("Return to ready after each swing. The app detects every repetition automatically.");
        repCounter.setText("0 / 3 REFERENCE SWINGS");
        coach.setText("Move naturally. Do not chase speed.");
        motionView.clear();
    }

    void startPracticeSet(){
        practice.clear(); practiceCount=0; latest=null;
        current.clear(); seriesArmed=true; recording=false; stage=Stage.PRACTICE;
        stageLabel.setText("STEP 3 OF 3");
        title.setText("10-rep forehand drill");
        instruction.setText("Swing naturally. Return to ready after each repetition. No taps until the set ends.");
        primary.setEnabled(false); primary.setAlpha(.45f); primary.setText("SET IN PROGRESS");
        repCounter.setText("0 / 10 PRACTICE SWINGS");
        coach.setText("Match your reference, then improve smoothness and follow-through.");
        motionView.clear();
    }

    void resetPractice(){
        practice.clear(); practiceCount=0; stage=Stage.READY; latest=null;
        primary.setText("START 10-SWING SET"); primary.setEnabled(true); primary.setAlpha(1f);
        stageLabel.setText("STEP 3 OF 3"); title.setText("Reference profile ready");
        instruction.setText("Start another 10-swing set when ready.");
        repCounter.setText("0 / 10 PRACTICE SWINGS");
        metrics.setText("No new swing recorded.");
        coach.setText("Build speed progressively and finish every swing.");
        session.setText(referenceSummary());
        motionView.clear();
    }

    @Override public void onSensorChanged(SensorEvent e){
        int t=e.sensor.getType();
        if(t==Sensor.TYPE_LINEAR_ACCELERATION) System.arraycopy(e.values,0,lastA,0,3);
        else if(t==Sensor.TYPE_GYROSCOPE) System.arraycopy(e.values,0,lastG,0,3);
        else if(t==Sensor.TYPE_ROTATION_VECTOR) SensorManager.getRotationMatrixFromVector(lastR,e.values);
        else return;

        if(baselineRunning){
            for(int i=0;i<3;i++){aBias[i]+=lastA[i];gBias[i]+=lastG[i];}
            baselineN++;
            float p=Math.min(1f,(System.nanoTime()-baselineStart)/2_500_000_000f);
            progress.setProgress((int)(1000*p));
            if(p>=1f) finishBaseline();
            return;
        }

        double am=mag(lastA[0]-aBias[0],lastA[1]-aBias[1],lastA[2]-aBias[2]);
        double gm=mag(lastG[0]-gBias[0],lastG[1]-gBias[1],lastG[2]-gBias[2]);
        live.setText(String.format(Locale.US,"Motion • %.1f m/s² • %.1f rad/s",am,gm));

        if(!seriesArmed) return;
        long now=e.timestamp;

        if(!recording){
            if(am>2.0 || gm>1.05){
                recording=true; recordStart=now; quietStart=0; current.clear();
                addSample(now);
                title.setText(stage==Stage.LEARN?"Reference swing detected":"Swing "+(practiceCount+1)+" detected");
            }
        }else{
            addSample(now);
            long elapsed=now-recordStart;
            boolean quiet=am<0.85 && gm<0.50;
            if(quiet && elapsed>380_000_000L){
                if(quietStart==0) quietStart=now;
                if(now-quietStart>280_000_000L) finishDetectedSwing();
            }else quietStart=0;
            if(elapsed>2_700_000_000L) finishDetectedSwing();
        }
    }

    void addSample(long t){
        float[] rel=mulMat(transpose(baseR),lastR);
        float ax=lastA[0]-aBias[0], ay=lastA[1]-aBias[1], az=lastA[2]-aBias[2];
        float[] ar=mulVec(rel,new float[]{ax,ay,az});
        float[] gr=mulVec(rel,new float[]{lastG[0]-gBias[0],lastG[1]-gBias[1],lastG[2]-gBias[2]});
        current.add(new Sample(t,new V(ar[0],ar[1],-ar[2]),new V(gr[0],gr[1],-gr[2])));
    }

    void finishDetectedSwing(){
        recording=false; quietStart=0;
        if(current.size()<14){ current.clear(); title.setText("Movement too short — try again"); return; }
        Metrics m=analyze(current);
        current.clear();
        if(m==null){ title.setText("Could not analyze — try again"); return; }
        latest=m;
        motionView.setMetrics(m);
        metrics.setText(formatMetrics(m));

        if(stage==Stage.LEARN){
            references.add(m); referenceCount++;
            repCounter.setText(referenceCount+" / 3 REFERENCE SWINGS");
            coach.setText(referenceCount<3 ? "Good. Return to ready and repeat slowly." : "Reference captured.");
            haptic();
            if(referenceCount>=3){
                seriesArmed=false;
                referenceProfile=average(references);
                stage=Stage.READY;
                stageLabel.setText("STEP 3 OF 3");
                title.setText("Reference profile ready");
                instruction.setText("Now do 10 natural forehands. The app compares each swing with your own reference.");
                primary.setText("START 10-SWING SET");
                primary.setEnabled(true); primary.setAlpha(1f);
                session.setText(referenceSummary());
                coach.setText("Your next goal: consistent path, progressive acceleration and complete follow-through.");
            }else title.setText("Reference "+referenceCount+" captured");
        }else if(stage==Stage.PRACTICE){
            practice.add(m); practiceCount++;
            repCounter.setText(practiceCount+" / 10 PRACTICE SWINGS");
            coach.setText(coachFor(m));
            session.setText(sessionSummary());
            haptic();
            if(practiceCount>=10){
                seriesArmed=false; stage=Stage.COMPLETE;
                title.setText("Set complete");
                instruction.setText("Review the pattern below. Start another set when you are ready.");
                primary.setText("START ANOTHER SET");
                primary.setEnabled(true); primary.setAlpha(1f);
                coach.setText(finalFocus());
                session.setText(sessionSummary());
            }else title.setText("Swing "+practiceCount+" captured");
        }
    }

    Metrics analyze(ArrayList<Sample> in){
        int n=in.size();
        double total=(in.get(n-1).t-in.get(0).t)/1e9;
        if(total<.18) return null;

        V[] vel=new V[n]; V[] pos=new V[n];
        double[] speed=new double[n], gyroM=new double[n], accelM=new double[n];
        vel[0]=new V(); pos[0]=new V();

        for(int i=1;i<n;i++){
            double dt=clamp((in.get(i).t-in.get(i-1).t)/1e9,.0005,.05);
            V aa=in.get(i-1).a.add(in.get(i).a).mul(.5*dt);
            vel[i]=vel[i-1].add(aa);
        }

        V drift=vel[n-1];
        double maxSpeed=0,maxGyro=0,maxAccel=0;
        int peakGyroIdx=0;
        for(int i=0;i<n;i++){
            double ti=(in.get(i).t-in.get(0).t)/1e9;
            vel[i]=vel[i].sub(drift.mul(ti/total));
            speed[i]=vel[i].mag();
            gyroM[i]=in.get(i).g.mag();
            accelM[i]=in.get(i).a.mag();
            if(speed[i]>maxSpeed) maxSpeed=speed[i];
            if(gyroM[i]>maxGyro){maxGyro=gyroM[i];peakGyroIdx=i;}
            if(accelM[i]>maxAccel) maxAccel=accelM[i];
        }
        for(int i=1;i<n;i++){
            double dt=clamp((in.get(i).t-in.get(i-1).t)/1e9,.0005,.05);
            pos[i]=pos[i-1].add(vel[i-1].add(vel[i]).mul(.5*dt));
        }

        int first=Math.max(1,(int)(n*.15)), last=Math.min(n-1,(int)(n*.88)), impact=first;
        double best=-1;
        for(int i=first;i<=last;i++){
            double sc=.45*speed[i]/Math.max(maxSpeed,1e-6)+.30*gyroM[i]/Math.max(maxGyro,1e-6)+.25*accelM[i]/Math.max(maxAccel,1e-6);
            if(sc>best){best=sc;impact=i;}
        }

        V iv=new V(); int c=0;
        for(int i=Math.max(0,impact-2);i<=Math.min(n-1,impact+2);i++){iv=iv.add(vel[i]);c++;}
        iv=iv.mul(1.0/Math.max(1,c));

        double vertical=Math.abs(iv.y), forward=Math.abs(iv.z), lateral=Math.abs(iv.x);
        double pathAngle=Math.toDegrees(Math.atan2(iv.y,Math.max(.001,Math.sqrt(iv.x*iv.x+iv.z*iv.z))));
        double forwardShare=100.0*forward/Math.max(.001,forward+vertical+lateral);
        double impactTime=(in.get(impact).t-in.get(0).t)/1e9;
        double followRatio=clamp((total-impactTime)/total,0,1);
        double peakOffsetMs=(in.get(peakGyroIdx).t-in.get(impact).t)/1e6;

        int inc=0,comp=0;
        int peakSpeedIdx=0;
        for(int i=1;i<n;i++) if(speed[i]>speed[peakSpeedIdx]) peakSpeedIdx=i;
        for(int i=2;i<=peakSpeedIdx;i++){
            if(speed[i]>=speed[i-1]) inc++;
            comp++;
        }
        double smoothness=comp==0?0:100.0*inc/comp;

        return new Metrics(total,maxSpeed,maxGyro,maxAccel,pathAngle,forwardShare,followRatio,peakOffsetMs,smoothness,impact,pos,speed);
    }

    Metrics average(ArrayList<Metrics> list){
        double duration=0,maxSpeed=0,maxGyro=0,maxAccel=0,path=0,forward=0,follow=0,peak=0,smooth=0;
        for(Metrics m:list){duration+=m.duration;maxSpeed+=m.maxSpeed;maxGyro+=m.maxGyro;maxAccel+=m.maxAccel;path+=m.pathAngle;forward+=m.forwardShare;follow+=m.followRatio;peak+=m.peakOffsetMs;smooth+=m.smoothness;}
        int n=Math.max(1,list.size());
        return new Metrics(duration/n,maxSpeed/n,maxGyro/n,maxAccel/n,path/n,forward/n,follow/n,peak/n,smooth/n,0,new V[]{new V()},new double[]{0});
    }

    String coachFor(Metrics m){
        if(m.smoothness<70) return "Build speed progressively. Avoid a sudden jerk before contact.";
        if(m.followRatio<0.30) return "Finish longer. Keep the motion going after virtual contact.";
        if(referenceProfile!=null && Math.abs(m.pathAngle-referenceProfile.pathAngle)>10) return "Your swing plane moved away from your reference. Re-center the path.";
        if(m.peakOffsetMs>90) return "Peak rotation came late. Start the acceleration earlier.";
        if(m.peakOffsetMs<-220) return "Peak rotation came early. Carry acceleration closer to contact.";
        return "Good sequence. Repeat this motion with the same rhythm.";
    }

    String finalFocus(){
        if(practice.isEmpty()) return "Complete a set first.";
        Metrics a=average(practice);
        return coachFor(a);
    }

    String referenceSummary(){
        if(referenceProfile==null) return "Reference not learned yet.";
        return String.format(Locale.US,
            "YOUR REFERENCE FOREHAND\nTempo %.2f s  •  Path %+.1f°  •  Follow %.0f%%  •  Smoothness %.0f%%",
            referenceProfile.duration,referenceProfile.pathAngle,referenceProfile.followRatio*100,referenceProfile.smoothness);
    }

    String sessionSummary(){
        if(practice.isEmpty()) return referenceSummary();
        Metrics a=average(practice);
        double pathConsistency=100-clamp(stdPath(practice)*4,0,100);
        return String.format(Locale.US,
            "%d / 10 swings\nConsistency %.0f%%  •  Avg smoothness %.0f%%\nAvg path %+.1f°  •  Avg follow %.0f%%",
            practice.size(),pathConsistency,a.smoothness,a.pathAngle,a.followRatio*100);
    }

    double stdPath(ArrayList<Metrics> list){
        if(list.size()<2) return 0;
        double mean=0; for(Metrics m:list) mean+=m.pathAngle; mean/=list.size();
        double s=0; for(Metrics m:list){double d=m.pathAngle-mean;s+=d*d;}
        return Math.sqrt(s/list.size());
    }

    String formatMetrics(Metrics m){
        String tempo=referenceProfile==null?"":String.format(Locale.US,"   ref %.2f",referenceProfile.duration);
        return String.format(Locale.US,
            "Tempo              %.2f s%s\nSwing speed proxy   %.1f km/h\nAngular speed       %.1f rad/s\nSwing path          %+.1f°\nForward share       %.0f%%\nFollow-through      %.0f%%\nPeak vs contact     %+.0f ms\nSmooth acceleration %.0f%%",
            m.duration,tempo,m.maxSpeed*3.6,m.maxGyro,m.pathAngle,m.forwardShare,m.followRatio*100,m.peakOffsetMs,m.smoothness);
    }

    void updateSensorStatus(){
        boolean ok=lin!=null&&gyro!=null&&rot!=null;
        live.setText("Sensors • linear accel "+yes(lin!=null)+" • gyro "+yes(gyro!=null)+" • rotation "+yes(rot!=null));
        if(!ok){live.setTextColor(RED);coach.setText("A required motion sensor is missing on this phone.");coach.setTextColor(RED);}
    }

    void haptic(){
        try{
            Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE);
            if(Build.VERSION.SDK_INT>=26) v.vibrate(VibrationEffect.createOneShot(45,VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(45);
        }catch(Exception ignored){}
    }

    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}

    class MotionView extends View{
        Paint p=new Paint(1); Metrics m;
        MotionView(Context c){super(c);}
        void setMetrics(Metrics mm){m=mm;invalidate();}
        void clear(){m=null;invalidate();}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            int w=getWidth(),h=getHeight();
            p.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD)); p.setTextSize(dp(12)); p.setColor(MUTED);
            c.drawText("SWING SIGNATURE",dp(16),dp(24),p);
            RectF box=new RectF(dp(16),dp(40),w-dp(16),h-dp(18));
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1)); p.setColor(LINE); c.drawRoundRect(box,dp(10),dp(10),p);
            if(m==null){p.setStyle(Paint.Style.FILL);p.setTextSize(dp(14));p.setColor(MUTED);c.drawText("Your motion trace will appear here.",dp(28),h/2f,p);return;}
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(3)); p.setColor(ACCENT);
            Path path=new Path();
            double max=0; for(double s:m.speed) max=Math.max(max,s);
            for(int i=0;i<m.speed.length;i++){
                float x=(float)(box.left+12+(box.width()-24)*i/Math.max(1,m.speed.length-1));
                float y=(float)(box.bottom-18-(box.height()-36)*(m.speed[i]/Math.max(.001,max)));
                if(i==0) path.moveTo(x,y); else path.lineTo(x,y);
            }
            c.drawPath(path,p);
            float ix=(float)(box.left+12+(box.width()-24)*m.impact/Math.max(1,m.speed.length-1));
            p.setColor(TEXT); p.setStrokeWidth(dp(1)); c.drawLine(ix,box.top+12,ix,box.bottom-12,p);
            p.setStyle(Paint.Style.FILL); p.setTextSize(dp(11)); p.setColor(MUTED); c.drawText("virtual contact",Math.min(ix+dp(5),box.right-dp(90)),box.top+dp(22),p);
        }
    }

    static class V{
        double x,y,z;
        V(){this(0,0,0);} V(double X,double Y,double Z){x=X;y=Y;z=Z;}
        V add(V o){return new V(x+o.x,y+o.y,z+o.z);}
        V sub(V o){return new V(x-o.x,y-o.y,z-o.z);}
        V mul(double s){return new V(x*s,y*s,z*s);}
        double mag(){return Math.sqrt(x*x+y*y+z*z);}
    }

    static class Sample{
        long t; V a,g;
        Sample(long T,V A,V G){t=T;a=A;g=G;}
    }

    static class Metrics{
        double duration,maxSpeed,maxGyro,maxAccel,pathAngle,forwardShare,followRatio,peakOffsetMs,smoothness;
        int impact; V[] pos; double[] speed;
        Metrics(double a,double b,double c,double d,double e,double f,double g,double h,double i,int j,V[]k,double[]l){
            duration=a;maxSpeed=b;maxGyro=c;maxAccel=d;pathAngle=e;forwardShare=f;followRatio=g;peakOffsetMs=h;smoothness=i;impact=j;pos=k;speed=l;
        }
    }

    float[] transpose(float[] a){return new float[]{a[0],a[3],a[6],a[1],a[4],a[7],a[2],a[5],a[8]};}
    float[] mulMat(float[] a,float[] b){
        float[] o=new float[9];
        for(int r=0;r<3;r++) for(int c=0;c<3;c++) o[r*3+c]=a[r*3]*b[c]+a[r*3+1]*b[3+c]+a[r*3+2]*b[6+c];
        return o;
    }
    float[] mulVec(float[] m,float[] v){return new float[]{
        m[0]*v[0]+m[1]*v[1]+m[2]*v[2],
        m[3]*v[0]+m[4]*v[1]+m[5]*v[2],
        m[6]*v[0]+m[7]*v[1]+m[8]*v[2]};}

    String yes(boolean v){return v?"✓":"—";}
    double mag(double x,double y,double z){return Math.sqrt(x*x+y*y+z*z);}
    static double clamp(double v,double a,double b){return Math.max(a,Math.min(b,v));}

    TextView tv(String s,float sp,int color,boolean bold){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setLineSpacing(0,1.15f);
        if(bold)v.setTypeface(v.getTypeface(),Typeface.BOLD);return v;
    }
    View card(View child){
        LinearLayout c=new LinearLayout(this);c.setPadding(dp(16),dp(14),dp(16),dp(14));c.setBackgroundColor(SURFACE);c.addView(child);return c;
    }
    Button button(String s,boolean primaryStyle){
        Button b=new Button(this);b.setText(s);b.setTextSize(15);b.setTypeface(b.getTypeface(),Typeface.BOLD);b.setMinHeight(dp(56));
        b.setTextColor(primaryStyle?BG:TEXT);
        GradientDrawable g=new GradientDrawable();g.setCornerRadius(dp(12));g.setColor(primaryStyle?ACCENT:SURFACE);g.setStroke(dp(1),primaryStyle?ACCENT:LINE);
        b.setBackground(g);return b;
    }
    Space space(int d){Space s=new Space(this);s.setLayoutParams(new LinearLayout.LayoutParams(1,dp(d)));return s;}
    int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
}
