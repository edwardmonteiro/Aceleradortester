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
        MUTED=Color.rgb(160,171,165), ACCENT=Color.rgb(216,255,87), RED=Color.rgb(255,116,116),
        LINE=Color.rgb(64,73,68), REF=Color.rgb(190,198,194);

    enum Stage { BASELINE, LEARN, READY, PRACTICE, COMPLETE }

    SensorManager sm; Sensor lin, gyro, rot;
    TextView stageLabel,title,instruction,live,repCounter,metrics,coach,session,learnHint,readyState;
    Button primary;
    ProgressBar progress;
    ShadowView shadowView;
    MotionBalanceView balanceView;
    LandmarkTimelineView landmarkView;

    Stage stage=Stage.BASELINE;
    boolean baselineRunning=false,seriesArmed=false,recording=false,waitingForReady=true;
    int referenceCount=0,practiceCount=0,baselineN=0;
    long baselineStart=0,recordStart=0,quietStart=0,readyStableStart=0,lastSwingEnd=0;

    final float[] aBias=new float[3],gBias=new float[3],lastA=new float[3],lastG=new float[3],
        lastR=new float[9],baseR=new float[9],swingBaseR=new float[9];
    final ArrayList<Sample> current=new ArrayList<>();
    final ArrayList<Metrics> references=new ArrayList<>();
    final ArrayList<Metrics> practice=new ArrayList<>();
    Metrics referenceProfile=null,latest=null;
    float[] referenceX=null,referenceY=null;

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
        registerSensorSafely(lin); registerSensorSafely(gyro); registerSensorSafely(rot);
    }
    @Override protected void onPause(){ if(sm!=null) sm.unregisterListener(this); super.onPause(); }

    void registerSensorSafely(Sensor sensor){
        if(sensor==null)return;
        try{
            boolean ok=sm.registerListener(this,sensor,5000);
            if(!ok)sm.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME);
        }catch(Exception e){
            try{sm.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME);}catch(Exception ignored){}
        }
    }

    void buildUi(){
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG); scroll.setFitsSystemWindows(true);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18),dp(18),dp(18),dp(38));
        scroll.addView(root,new ScrollView.LayoutParams(-1,-2));

        root.addView(tv("TENNIS DRILL  V3.4",12,MUTED,true));
        root.addView(tv("Shadow Forehand",32,TEXT,true));
        root.addView(tv("Move. Compare. Learn.",15,MUTED,false));

        TextView safety=tv("Screen toward you • secure grip • clear the area",11,MUTED,false);
        safety.setPadding(0,dp(8),0,0); root.addView(safety);

        root.addView(space(18));
        stageLabel=tv("STEP 1 OF 3",12,MUTED,true); root.addView(stageLabel);
        title=tv("Calibrate ready position",24,TEXT,true); root.addView(title);
        instruction=tv("Hold your normal forehand ready position. Stay still for 2.5 seconds.",14,MUTED,false);
        instruction.setPadding(0,dp(6),0,dp(12)); root.addView(instruction);

        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000); progress.setVisibility(View.GONE); root.addView(progress,new LinearLayout.LayoutParams(-1,dp(8)));
        root.addView(space(10));

        primary=button("START CALIBRATION",true); primary.setOnClickListener(v->onPrimary()); root.addView(primary);

        root.addView(space(14));
        readyState=tv("●  READY  not armed",14,MUTED,true); readyState.setGravity(Gravity.CENTER); root.addView(chip(readyState));
        root.addView(space(10));
        live=tv("Sensors checking…",11,MUTED,false); live.setGravity(Gravity.CENTER); root.addView(live);

        root.addView(space(18));
        repCounter=tv("0 / 3  REFERENCE",17,TEXT,true); repCounter.setGravity(Gravity.CENTER); root.addView(card(repCounter));

        root.addView(space(14));
        learnHint=tv("REFERENCE SHADOW",12,MUTED,true); root.addView(learnHint);
        shadowView=new ShadowView(this); shadowView.setBackground(roundRect(SURFACE,18,LINE,1));
        root.addView(shadowView,new LinearLayout.LayoutParams(-1,dp(300)));

        root.addView(space(16));
        root.addView(tv("FORWARD ↔ UPWARD",12,MUTED,true));
        balanceView=new MotionBalanceView(this); balanceView.setBackground(roundRect(SURFACE,18,LINE,1));
        root.addView(balanceView,new LinearLayout.LayoutParams(-1,dp(158)));

        root.addView(space(16));
        root.addView(tv("STROKE LANDMARKS",12,MUTED,true));
        landmarkView=new LandmarkTimelineView(this); landmarkView.setBackground(roundRect(SURFACE,18,LINE,1));
        root.addView(landmarkView,new LinearLayout.LayoutParams(-1,dp(230)));

        root.addView(space(16));
        root.addView(tv("LATEST SWING",12,MUTED,true));
        metrics=tv("No swing recorded yet.",16,TEXT,false); metrics.setPadding(0,dp(6),0,0); root.addView(metrics);

        root.addView(space(16));
        root.addView(tv("ONE THING TO LEARN",12,MUTED,true));
        coach=tv("Complete calibration first.",18,ACCENT,true); coach.setPadding(0,dp(6),0,0); root.addView(coach);

        root.addView(space(18));
        root.addView(tv("SESSION",12,MUTED,true));
        session=tv("3 slow forehands create your personal shadow.",14,MUTED,false); root.addView(session);

        setContentView(scroll);
    }

    void onPrimary(){
        if(stage==Stage.BASELINE)beginBaseline();
        else if(stage==Stage.LEARN)startReferenceSet();
        else if(stage==Stage.READY)startPracticeSet();
        else if(stage==Stage.COMPLETE)resetPractice();
    }

    void beginBaseline(){
        if(lin==null||gyro==null||rot==null){
            title.setText("Required sensors unavailable");
            coach.setText("This phone needs linear acceleration, gyroscope and rotation vector sensors.");
            coach.setTextColor(RED); return;
        }
        baselineRunning=true; baselineStart=System.nanoTime(); baselineN=0;
        Arrays.fill(aBias,0); Arrays.fill(gBias,0);
        progress.setProgress(0); progress.setVisibility(View.VISIBLE);
        primary.setEnabled(false); primary.setAlpha(.5f);
        title.setText("Stay in ready…"); instruction.setText("Keep the screen facing you and the phone still.");
    }

    void finishBaseline(){
        for(int i=0;i<3;i++){aBias[i]/=Math.max(1,baselineN);gBias[i]/=Math.max(1,baselineN);}
        System.arraycopy(lastR,0,baseR,0,9);
        baselineRunning=false; stage=Stage.LEARN; progress.setVisibility(View.GONE);
        stageLabel.setText("STEP 2 OF 3"); title.setText("Teach me 3 slow forehands");
        instruction.setText("Do 3 complete slow forehands. After each finish, return to the same ready position. The app waits for ready before accepting the next swing.");
        primary.setText("START 3 REFERENCE SWINGS"); primary.setEnabled(true); primary.setAlpha(1f);
        repCounter.setText("0 / 3  REFERENCE");
        coach.setText("Watch the line: each swing builds your central shadow.");
    }

    void startReferenceSet(){
        references.clear(); referenceCount=0; practiceCount=0; latest=null; referenceX=null; referenceY=null;
        current.clear(); seriesArmed=true; recording=false; waitingForReady=true; readyStableStart=0; stage=Stage.LEARN; readyState.setText("●  SETTLE INTO READY"); readyState.setTextColor(MUTED);
        primary.setEnabled(false); primary.setAlpha(.45f); primary.setText("LISTENING…");
        title.setText("Return to READY first"); instruction.setText("When ready is stable, the app arms automatically. Then perform one full slow forehand.");
        repCounter.setText("0 / 3  REFERENCE"); coach.setText("Ready → takeback → accelerate → finish → ready.");
        shadowView.resetAll(); balanceView.setMetrics(null,null); landmarkView.setMetrics(null,null);
    }

    void startPracticeSet(){
        practice.clear(); practiceCount=0; latest=null; current.clear();
        seriesArmed=true; recording=false; waitingForReady=true; readyStableStart=0; stage=Stage.PRACTICE; readyState.setText("●  SETTLE INTO READY"); readyState.setTextColor(MUTED);
        stageLabel.setText("STEP 3 OF 3"); title.setText("Return to READY");
        instruction.setText("Do 10 natural forehands. Your live line is drawn over the central reference.");
        primary.setEnabled(false); primary.setAlpha(.45f); primary.setText("SET IN PROGRESS");
        repCounter.setText("0 / 10  TRAINING");
        coach.setText("Match the shape first. Then explore forward versus upward motion.");
        shadowView.startPractice(referenceX,referenceY); balanceView.setMetrics(null,referenceProfile); landmarkView.setMetrics(null,referenceProfile);
    }

    void resetPractice(){
        practice.clear(); practiceCount=0; latest=null; stage=Stage.READY; seriesArmed=false; readyState.setText("●  READY  not armed"); readyState.setTextColor(MUTED);
        primary.setText("START 10-SWING SET"); primary.setEnabled(true); primary.setAlpha(1f);
        stageLabel.setText("STEP 3 OF 3"); title.setText("Reference shadow ready");
        instruction.setText("Start another 10-swing set when ready.");
        repCounter.setText("0 / 10  TRAINING"); metrics.setText("No new swing recorded.");
        coach.setText("Try to reproduce your central shadow before changing the motion.");
        session.setText(referenceSummary()); shadowView.startPractice(referenceX,referenceY); balanceView.setMetrics(null,referenceProfile); landmarkView.setMetrics(null,referenceProfile);
    }

    @Override public void onSensorChanged(SensorEvent e){
        int t=e.sensor.getType();
        if(t==Sensor.TYPE_LINEAR_ACCELERATION)System.arraycopy(e.values,0,lastA,0,3);
        else if(t==Sensor.TYPE_GYROSCOPE)System.arraycopy(e.values,0,lastG,0,3);
        else if(t==Sensor.TYPE_ROTATION_VECTOR)SensorManager.getRotationMatrixFromVector(lastR,e.values);
        else return;

        if(baselineRunning){
            for(int i=0;i<3;i++){aBias[i]+=lastA[i];gBias[i]+=lastG[i];} baselineN++;
            float p=Math.min(1f,(System.nanoTime()-baselineStart)/2_500_000_000f);
            progress.setProgress((int)(1000*p)); if(p>=1f)finishBaseline(); return;
        }

        double am=mag(lastA[0]-aBias[0],lastA[1]-aBias[1],lastA[2]-aBias[2]);
        double gm=mag(lastG[0]-gBias[0],lastG[1]-gBias[1],lastG[2]-gBias[2]);
        double readyAngle=readyAngleDeg();
        live.setText(recording ? "Capturing motion…" : (waitingForReady ? "Waiting for a stable ready position" : "Ready sensor lock active"));

        if(!seriesArmed)return;
        long now=e.timestamp;

        if(waitingForReady){
            // V3.2: READY is a stable resting state, not a precise remembered angle.
            // Once stable, this exact orientation becomes the local zero for the next swing.
            boolean ready=am<0.75 && gm<0.42;
            if(ready){
                if(readyStableStart==0)readyStableStart=now;
                long held=now-readyStableStart;
                if(held>340_000_000L && now-lastSwingEnd>520_000_000L){
                    waitingForReady=false; readyStableStart=0;
                    System.arraycopy(lastR,0,swingBaseR,0,9);
                    current.clear();
                    readyState.setText("●  READY ✓   SWING");
                    readyState.setTextColor(ACCENT); pulseReady();
                    title.setText(stage==Stage.LEARN?"READY ✓ — make the reference swing":"READY ✓ — swing "+(practiceCount+1));
                    shadowView.beginLive();
                }else{
                    readyState.setText("●  HOLD READY");
                    readyState.setTextColor(MUTED);
                }
            }else{
                readyStableStart=0;
                readyState.setText("●  SETTLE INTO READY");
                readyState.setTextColor(MUTED);
            }
            return;
        }

        if(!recording){
            // Keep a short pre-roll after READY so takeback is not cut off.
            addSample(now);
            while(current.size()>160) current.remove(0);
            if(am>1.35 || gm>0.72){
                recording=true;
                recordStart=current.isEmpty()?now:current.get(0).t;
                quietStart=0;
                readyState.setText("●  SWINGING");
                readyState.setTextColor(TEXT);
                title.setText(stage==Stage.LEARN?"Reference swing in progress":"Swing "+(practiceCount+1)+" in progress");
            }
        }else{
            addSample(now);
            shadowView.setLive(buildLiveTrace(current));
            long elapsed=now-recordStart;
            boolean quiet=am<0.85 && gm<0.50;
            if(quiet && elapsed>420_000_000L){
                if(quietStart==0)quietStart=now;
                if(now-quietStart>240_000_000L)finishDetectedSwing();
            }else quietStart=0;
            if(elapsed>2_650_000_000L)finishDetectedSwing();
        }
    }

    double readyAngleDeg(){
        float[] rel=mulMat(transpose(swingBaseR),lastR);
        double trace=rel[0]+rel[4]+rel[8];
        double cos=clamp((trace-1.0)/2.0,-1,1);
        return Math.toDegrees(Math.acos(cos));
    }

    void addSample(long t){
        float[] rel=mulMat(transpose(baseR),lastR);
        float ax=lastA[0]-aBias[0],ay=lastA[1]-aBias[1],az=lastA[2]-aBias[2];
        float[] ar=mulVec(rel,new float[]{ax,ay,az});
        float[] gr=mulVec(rel,new float[]{lastG[0]-gBias[0],lastG[1]-gBias[1],lastG[2]-gBias[2]});
        float[] up=mulVec(rel,new float[]{0,1,0});
        current.add(new Sample(t,new V(ar[0],ar[1],-ar[2]),new V(gr[0],gr[1],-gr[2]),new V(up[0],up[1],up[2])));
    }

    float[][] buildLiveTrace(ArrayList<Sample> list){
        // Fast live estimate of actual motion path: horizontal axis = forward,
        // vertical axis = upward. Drift is corrected precisely after the swing ends.
        int n=list.size(); if(n<3)return null;
        V[] vel=new V[n]; V[] pos=new V[n]; vel[0]=new V(); pos[0]=new V();
        for(int i=1;i<n;i++){
            double dt=clamp((list.get(i).t-list.get(i-1).t)/1e9,.0005,.04);
            vel[i]=vel[i-1].add(list.get(i-1).a.add(list.get(i).a).mul(.5*dt));
            pos[i]=pos[i-1].add(vel[i-1].add(vel[i]).mul(.5*dt));
        }
        return traceFromPositions(pos,Math.min(64,n));
    }

    void finishDetectedSwing(){
        recording=false; quietStart=0; waitingForReady=true; readyStableStart=0; lastSwingEnd=System.nanoTime(); readyState.setText("●  RETURN TO READY"); readyState.setTextColor(MUTED);
        if(current.size()<14){current.clear();title.setText("Too short — return to READY");shadowView.endLive(false);return;}
        Metrics m=analyze(current); current.clear();
        if(m==null){title.setText("Could not analyze — return to READY");shadowView.endLive(false);return;}
        latest=m; metrics.setText(formatMetrics(m)); balanceView.setMetrics(m,referenceProfile); landmarkView.setMetrics(m,referenceProfile);

        if(stage==Stage.LEARN){
            references.add(m); referenceCount++; shadowView.addReference(m.traceX,m.traceY);
            repCounter.setText(referenceCount+" / 3  REFERENCE"); haptic();
            if(referenceCount>=3){
                referenceProfile=average(references); buildReferenceTrace();
                seriesArmed=false; stage=Stage.READY;
                stageLabel.setText("STEP 3 OF 3"); title.setText("Central shadow created");
                instruction.setText("The bright center line is the average of your 3 reference forehands. Now train 10 repetitions over it.");
                primary.setText("START 10-SWING SET"); primary.setEnabled(true); primary.setAlpha(1f);
                coach.setText("First match the shadow. Then learn how changing Forward / Upward changes the stroke shape.");
                session.setText(referenceSummary()); shadowView.startPractice(referenceX,referenceY);
            }else{
                title.setText("Reference "+referenceCount+" captured — return to READY");
                coach.setText("The next reference is accepted only after you return to ready.");
            }
        }else if(stage==Stage.PRACTICE){
            practice.add(m); practiceCount++; shadowView.endLive(true);
            repCounter.setText(practiceCount+" / 10  TRAINING"); coach.setText(coachFor(m)); session.setText(sessionSummary()); haptic();
            if(practiceCount>=10){
                seriesArmed=false; stage=Stage.COMPLETE; title.setText("Set complete");
                instruction.setText("Compare the live traces with your central shadow and the Forward / Upward balance.");
                primary.setText("START ANOTHER SET"); primary.setEnabled(true); primary.setAlpha(1f);
                coach.setText(finalFocus());
            }else title.setText("Swing "+practiceCount+" captured — return to READY");
        }
    }

    Metrics analyze(ArrayList<Sample> in){
        int n=in.size(); double total=(in.get(n-1).t-in.get(0).t)/1e9; if(total<.18)return null;
        V[] vel=new V[n]; V[] pos=new V[n]; double[] speed=new double[n],gyroM=new double[n],accelM=new double[n];
        vel[0]=new V(); pos[0]=new V(); double maxSpeed=0,maxGyro=0,maxAccel=0; int peakGyroIdx=0;

        for(int i=1;i<n;i++){
            double dt=clamp((in.get(i).t-in.get(i-1).t)/1e9,.0005,.05);
            vel[i]=vel[i-1].add(in.get(i-1).a.add(in.get(i).a).mul(.5*dt));
        }
        V drift=vel[n-1];
        for(int i=0;i<n;i++){
            double ti=(in.get(i).t-in.get(0).t)/1e9; vel[i]=vel[i].sub(drift.mul(ti/total));
            speed[i]=vel[i].mag(); gyroM[i]=in.get(i).g.mag(); accelM[i]=in.get(i).a.mag();
            if(speed[i]>maxSpeed)maxSpeed=speed[i];
            if(gyroM[i]>maxGyro){maxGyro=gyroM[i];peakGyroIdx=i;}
            if(accelM[i]>maxAccel)maxAccel=accelM[i];
        }
        for(int i=1;i<n;i++){
            double dt=clamp((in.get(i).t-in.get(i-1).t)/1e9,.0005,.04);
            pos[i]=pos[i-1].add(vel[i-1].add(vel[i]).mul(.5*dt));
        }

        int first=Math.max(1,(int)(n*.15)),last=Math.min(n-1,(int)(n*.88)),impact=first; double best=-1;
        for(int i=first;i<=last;i++){
            double sc=.45*speed[i]/Math.max(maxSpeed,1e-6)+.30*gyroM[i]/Math.max(maxGyro,1e-6)+.25*accelM[i]/Math.max(maxAccel,1e-6);
            if(sc>best){best=sc;impact=i;}
        }

        V iv=new V(); int c=0;
        for(int i=Math.max(0,impact-2);i<=Math.min(n-1,impact+2);i++){iv=iv.add(vel[i]);c++;}
        iv=iv.mul(1.0/Math.max(1,c));

        double upward=Math.max(0,iv.y),forward=Math.abs(iv.z);
        double fu=Math.max(.001,forward+upward);
        double forwardPct=100*forward/fu,upwardPct=100*upward/fu;
        double pathAngle=Math.toDegrees(Math.atan2(iv.y,Math.max(.001,Math.abs(iv.z))));
        double impactTime=(in.get(impact).t-in.get(0).t)/1e9;
        double followRatio=clamp((total-impactTime)/total,0,1);
        double peakOffsetMs=(in.get(peakGyroIdx).t-in.get(impact).t)/1e6;

        int peakSpeedIdx=0,inc=0,comp=0;
        for(int i=1;i<n;i++)if(speed[i]>speed[peakSpeedIdx])peakSpeedIdx=i;
        for(int i=2;i<=peakSpeedIdx;i++){if(speed[i]>=speed[i-1])inc++;comp++;}
        double smoothness=comp==0?0:100.0*inc/comp;

        Landmarks lm=detectLandmarks(in,pos,vel,speed,gyroM,accelM,impact,peakGyroIdx,maxSpeed,maxGyro,total);
        float[][] trace=traceFromPositions(pos,64);
        return new Metrics(total,maxSpeed,maxGyro,maxAccel,pathAngle,forwardPct,upwardPct,followRatio,peakOffsetMs,smoothness,impact,speed,trace[0],trace[1],lm);
    }

    Landmarks detectLandmarks(ArrayList<Sample> in,V[] pos,V[] vel,double[] speed,double[] gyroM,double[] accelM,
                              int impact,int peakGyroIdx,double maxSpeed,double maxGyro,double total){
        int n=in.size();
        int takebackStart=Math.max(1,(int)(n*.06));
        double moveThreshold=Math.max(.45,maxGyro*.14);
        for(int i=takebackStart;i<Math.min(impact,n-1);i++){
            if(gyroM[i]>moveThreshold || accelM[i]>1.0){takebackStart=i;break;}
        }

        int maxTakeback=takebackStart;
        double minForward=pos[takebackStart].z;
        for(int i=takebackStart;i<Math.max(takebackStart+1,impact);i++){
            if(pos[i].z<minForward){minForward=pos[i].z;maxTakeback=i;}
        }

        int drive=Math.min(Math.max(maxTakeback+1,takebackStart+1),Math.max(1,impact));
        double driveThreshold=Math.max(.10,maxSpeed*.13);
        for(int i=drive;i<=impact;i++){
            if(vel[i].z>driveThreshold && speed[i]>=speed[Math.max(0,i-1)]){drive=i;break;}
        }

        int split=-1;
        double bestSplit=0;
        int splitEnd=Math.min(takebackStart,Math.max(2,(int)(n*.38)));
        for(int i=2;i<splitEnd;i++){
            double verticalPulse=Math.abs(in.get(i).a.y);
            double score=verticalPulse*(1.0-clamp(gyroM[i]/Math.max(.8,maxGyro),0,.75));
            if(score>bestSplit){bestSplit=score;split=i;}
        }
        if(bestSplit<1.15)split=-1;

        int followEnd=n-1;
        int minFollow=Math.min(n-1,impact+Math.max(3,(int)(n*.05)));
        for(int i=minFollow;i<n;i++){
            if(speed[i]<Math.max(.08,maxSpeed*.16)){followEnd=i;break;}
        }

        double maxLat=0,maxOther=.001;
        for(int i=takebackStart;i<=impact;i++){
            maxLat=Math.max(maxLat,Math.abs(pos[i].x));
            maxOther=Math.max(maxOther,Math.abs(pos[i].z)+Math.abs(pos[i].y));
        }
        double lateralPrepPct=100*maxLat/(maxLat+maxOther);

        long t0=in.get(0).t;
        Landmarks lm=new Landmarks();
        lm.splitDetected=split>=0;
        lm.splitT=split>=0?frac(in.get(split).t,t0,total):Double.NaN;
        lm.takebackT=frac(in.get(takebackStart).t,t0,total);
        lm.driveT=frac(in.get(drive).t,t0,total);
        lm.peakT=frac(in.get(peakGyroIdx).t,t0,total);
        lm.contactT=frac(in.get(impact).t,t0,total);
        lm.followT=frac(in.get(followEnd).t,t0,total);
        lm.lateralPrepPct=lateralPrepPct;
        if(split>=0){
            lm.loadToDriveMs=(in.get(drive).t-in.get(split).t)/1e6;
            lm.loadToContactMs=(in.get(impact).t-in.get(split).t)/1e6;
        }
        return lm;
    }

    double frac(long t,long t0,double total){
        return clamp(((t-t0)/1e9)/Math.max(.001,total),0,1);
    }

    float[][] traceFromPositions(V[] pos,int points){
        int n=pos.length; float[] x=new float[points],y=new float[points];
        for(int j=0;j<points;j++){
            int idx=(int)Math.round(j*(n-1.0)/Math.max(1,points-1));
            // In our calibrated phone frame: +Z is forward and +Y is upward.
            x[j]=(float)pos[idx].z;
            y[j]=(float)pos[idx].y;
        }
        normalizeTrace(x,y); smoothTrace(x,y); return new float[][]{x,y};
    }

    void normalizeTrace(float[] x,float[] y){
        if(x.length==0)return;
        float x0=x[0],y0=y[0],max=0;
        for(int i=0;i<x.length;i++){x[i]-=x0;y[i]-=y0;max=Math.max(max,Math.max(Math.abs(x[i]),Math.abs(y[i])));}
        if(max<.001f)max=1;
        for(int i=0;i<x.length;i++){x[i]/=max;y[i]/=max;}
    }

    void smoothTrace(float[] x,float[] y){
        if(x==null||x.length<5)return;
        float[] ox=x.clone(),oy=y.clone();
        for(int i=2;i<x.length-2;i++){
            x[i]=(ox[i-2]+2*ox[i-1]+3*ox[i]+2*ox[i+1]+ox[i+2])/9f;
            y[i]=(oy[i-2]+2*oy[i-1]+3*oy[i]+2*oy[i+1]+oy[i+2])/9f;
        }
    }

    void buildReferenceTrace(){
        int p=64; referenceX=new float[p]; referenceY=new float[p];
        for(Metrics m:references)for(int i=0;i<p;i++){referenceX[i]+=m.traceX[i];referenceY[i]+=m.traceY[i];}
        for(int i=0;i<p;i++){referenceX[i]/=references.size();referenceY[i]/=references.size();}
    }

    Metrics average(ArrayList<Metrics> list){
        double duration=0,maxSpeed=0,maxGyro=0,maxAccel=0,path=0,forward=0,upward=0,follow=0,peak=0,smooth=0;
        for(Metrics m:list){duration+=m.duration;maxSpeed+=m.maxSpeed;maxGyro+=m.maxGyro;maxAccel+=m.maxAccel;path+=m.pathAngle;forward+=m.forwardPct;upward+=m.upwardPct;follow+=m.followRatio;peak+=m.peakOffsetMs;smooth+=m.smoothness;}
        int n=Math.max(1,list.size());
        Landmarks avgLm=averageLandmarks(list);
        return new Metrics(duration/n,maxSpeed/n,maxGyro/n,maxAccel/n,path/n,forward/n,upward/n,follow/n,peak/n,smooth/n,0,new double[]{0},new float[64],new float[64],avgLm);
    }

    Landmarks averageLandmarks(ArrayList<Metrics> list){
        Landmarks out=new Landmarks(); int n=Math.max(1,list.size()),splitN=0;
        double take=0,drive=0,peak=0,contact=0,follow=0,lat=0,splitT=0,ltd=0,ltc=0;
        for(Metrics m:list){
            if(m.lm==null)continue;
            take+=m.lm.takebackT;drive+=m.lm.driveT;peak+=m.lm.peakT;contact+=m.lm.contactT;follow+=m.lm.followT;lat+=m.lm.lateralPrepPct;
            if(m.lm.splitDetected){splitN++;splitT+=m.lm.splitT;ltd+=m.lm.loadToDriveMs;ltc+=m.lm.loadToContactMs;}
        }
        out.takebackT=take/n;out.driveT=drive/n;out.peakT=peak/n;out.contactT=contact/n;out.followT=follow/n;out.lateralPrepPct=lat/n;
        out.splitDetected=splitN>0;
        if(splitN>0){out.splitT=splitT/splitN;out.loadToDriveMs=ltd/splitN;out.loadToContactMs=ltc/splitN;}
        return out;
    }

    String coachFor(Metrics m){
        if(referenceProfile!=null){
            double shape=traceDistance(m.traceX,m.traceY,referenceX,referenceY);
            if(shape>.23)return "Match the central shadow first. Your swing shape drifted more than usual.";
            if(m.lm!=null && referenceProfile.lm!=null && m.lm.splitDetected && referenceProfile.lm.splitDetected){
                double d=m.lm.loadToDriveMs-referenceProfile.lm.loadToDriveMs;
                if(d>120)return "Your load-to-drive sequence was slower than your reference. Connect the lower-body load to the forward drive sooner.";
                if(d<-120)return "Your drive started much earlier after the load. Keep the sequence controlled instead of rushing it.";
            }
        }
        if(m.smoothness<70)return "Build speed progressively. Smooth acceleration is the priority.";
        if(m.followRatio<.30)return "Finish longer. Do not stop at the virtual contact.";
        if(m.forwardPct>78)return "This swing was strongly FORWARD. Feel how it travels through the ball.";
        if(m.upwardPct>45)return "This swing had more UPWARD motion. Feel the stronger low-to-high component.";
        return "Balanced motion. Repeat it and notice how Forward and Upward change the feel.";
    }

    double traceDistance(float[] ax,float[] ay,float[] bx,float[] by){
        if(ax==null||bx==null)return 0; int n=Math.min(ax.length,bx.length); double s=0;
        for(int i=0;i<n;i++){double dx=ax[i]-bx[i],dy=ay[i]-by[i];s+=Math.sqrt(dx*dx+dy*dy);}
        return n==0?0:s/n;
    }

    String finalFocus(){return practice.isEmpty()?"Complete a set first.":coachFor(average(practice));}

    String referenceSummary(){
        if(referenceProfile==null)return "Reference not learned yet.";
        return String.format(Locale.US,"YOUR REFERENCE\nTempo %.2f s • Forward %.0f%% • Upward %.0f%% • Follow %.0f%%",
            referenceProfile.duration,referenceProfile.forwardPct,referenceProfile.upwardPct,referenceProfile.followRatio*100);
    }

    String sessionSummary(){
        if(practice.isEmpty())return referenceSummary();
        Metrics a=average(practice); double consistency=100-clamp(stdPath(practice)*4,0,100);
        return String.format(Locale.US,"%d / 10 swings\nConsistency %.0f%% • Smoothness %.0f%%\nForward %.0f%% • Upward %.0f%% • Follow %.0f%%",
            practice.size(),consistency,a.smoothness,a.forwardPct,a.upwardPct,a.followRatio*100);
    }

    double stdPath(ArrayList<Metrics> list){
        if(list.size()<2)return 0; double mean=0; for(Metrics m:list)mean+=m.pathAngle; mean/=list.size();
        double s=0; for(Metrics m:list){double d=m.pathAngle-mean;s+=d*d;} return Math.sqrt(s/list.size());
    }

    String formatMetrics(Metrics m){
        String learn;
        if(m.forwardPct>=75)learn="More THROUGH / forward";
        else if(m.upwardPct>=45)learn="More LOW-TO-HIGH / upward";
        else learn="Balanced forward + upward";
        double match=(referenceX==null)?Double.NaN:100.0-clamp(traceDistance(m.traceX,m.traceY,referenceX,referenceY)*145.0,0,100);
        String matchText=Double.isNaN(match)?"—":String.format(Locale.US,"%.0f%%",match);
        String split=(m.lm!=null&&m.lm.splitDetected)?String.format(Locale.US,"%.0f ms load→drive",m.lm.loadToDriveMs):"not detected";
        String lateral=(m.lm==null)?"—":String.format(Locale.US,"%.0f%%",m.lm.lateralPrepPct);
        return String.format(Locale.US,
            "Shadow match     %s\nForward / Up      %.0f%% / %.0f%%\nSwing path        %+.1f°\nFollow-through    %.0f%%\nSmoothness        %.0f%%\nSplit/load proxy  %s\nLateral prep      %s proxy\nTempo             %.2f s\n\n%s",
            matchText,m.forwardPct,m.upwardPct,m.pathAngle,m.followRatio*100,m.smoothness,split,lateral,m.duration,learn);
    }

    void updateSensorStatus(){
        boolean ok=lin!=null&&gyro!=null&&rot!=null;
        live.setText(ok ? "Sensors ready" : "Motion sensor unavailable");
        if(!ok){live.setTextColor(RED);coach.setText("A required motion sensor is missing.");coach.setTextColor(RED);}
    }

    void haptic(){
        try{
            Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE);
            if(Build.VERSION.SDK_INT>=26)v.vibrate(VibrationEffect.createOneShot(45,VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(45);
        }catch(Exception ignored){}
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}

    class ShadowView extends View{
        Paint p=new Paint(1);
        final ArrayList<float[][]> refs=new ArrayList<>();
        float[] centerX,centerY,liveX,liveY,lastX,lastY;
        boolean practiceMode=false;

        ShadowView(Context c){super(c);}
        void resetAll(){refs.clear();centerX=centerY=liveX=liveY=lastX=lastY=null;practiceMode=false;invalidate();}
        void addReference(float[] x,float[] y){refs.add(new float[][]{x.clone(),y.clone()});lastX=x;lastY=y;liveX=liveY=null;invalidate();}
        void startPractice(float[] x,float[] y){centerX=x;centerY=y;practiceMode=true;liveX=liveY=lastX=lastY=null;invalidate();}
        void beginLive(){liveX=liveY=null;invalidate();}
        void setLive(float[][] a){if(a!=null){liveX=a[0];liveY=a[1];invalidate();}}
        void endLive(boolean keep){if(keep&&liveX!=null){lastX=liveX;lastY=liveY;}liveX=liveY=null;invalidate();}

        @Override protected void onDraw(Canvas c){
            super.onDraw(c); int w=getWidth(),h=getHeight();
            p.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));p.setTextSize(dp(11));p.setColor(MUTED);p.setStyle(Paint.Style.FILL);
            c.drawText(practiceMode?"SHADOW  •  live vs reference":"REFERENCE  •  building your shadow",dp(16),dp(24),p);
            RectF box=new RectF(dp(16),dp(42),w-dp(16),h-dp(18));
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));p.setColor(LINE);c.drawRoundRect(box,dp(10),dp(10),p);
            p.setStrokeWidth(dp(1));c.drawLine(box.centerX(),box.top+8,box.centerX(),box.bottom-8,p);c.drawLine(box.left+8,box.centerY(),box.right-8,box.centerY(),p);

            if(!practiceMode){
                int idx=0; for(float[][] r:refs){drawTrace(c,box,r[0],r[1],Color.rgb(105+idx*25,115+idx*25,110+idx*25),dp(2));idx++;}
                if(refs.size()>=2){
                    float[][] avg=averageRefs(); drawTrace(c,box,avg[0],avg[1],ACCENT,dp(4));
                }
            }else if(centerX!=null) drawTrace(c,box,centerX,centerY,REF,dp(4));

            if(lastX!=null)drawTrace(c,box,lastX,lastY,Color.rgb(110,130,120),dp(2));
            if(liveX!=null)drawTrace(c,box,liveX,liveY,ACCENT,dp(4));

            p.setStyle(Paint.Style.FILL);p.setTextSize(dp(10));p.setColor(MUTED);
            c.drawText("START",box.left+dp(10),box.bottom-dp(10),p);
            c.drawText("FORWARD →",box.right-dp(82),box.centerY()+dp(20),p);
            c.drawText("UP ↑",box.centerX()+dp(8),box.top+dp(16),p);
        }

        float[][] averageRefs(){
            int n=64;float[] x=new float[n],y=new float[n];
            for(float[][] r:refs)for(int i=0;i<n;i++){x[i]+=r[0][i];y[i]+=r[1][i];}
            for(int i=0;i<n;i++){x[i]/=refs.size();y[i]/=refs.size();}return new float[][]{x,y};
        }

        void drawTrace(Canvas c,RectF b,float[] x,float[] y,int color,float width){
            if(x==null||x.length<2)return;
            float[] px=new float[x.length],py=new float[y.length];
            for(int i=0;i<x.length;i++){px[i]=b.centerX()+x[i]*(b.width()*.38f);py[i]=b.centerY()-y[i]*(b.height()*.38f);}
            Path path=new Path(); path.moveTo(px[0],py[0]);
            for(int i=1;i<px.length;i++){
                float mx=(px[i-1]+px[i])/2f,my=(py[i-1]+py[i])/2f;
                path.quadTo(px[i-1],py[i-1],mx,my);
            }
            path.lineTo(px[px.length-1],py[py.length-1]);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(width);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setColor(color);c.drawPath(path,p);
            p.setStyle(Paint.Style.FILL);p.setColor(color);c.drawCircle(px[0],py[0],dp(3),p);c.drawCircle(px[px.length-1],py[py.length-1],dp(4),p);
        }
    }

    class LandmarkTimelineView extends View{
        Paint p=new Paint(1); Metrics m,ref;
        LandmarkTimelineView(Context c){super(c);}
        void setMetrics(Metrics mm,Metrics rr){m=mm;ref=rr;invalidate();}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c); int w=getWidth(),h=getHeight();
            p.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));p.setStyle(Paint.Style.FILL);
            p.setTextSize(dp(12));p.setColor(MUTED);c.drawText("MOVEMENT TIMELINE",dp(16),dp(24),p);

            Metrics use=m!=null?m:ref;
            if(use==null||use.lm==null){
                p.setTextSize(dp(14));p.setColor(MUTED);c.drawText("Landmarks appear after a swing.",dp(16),h/2f,p);return;
            }
            Landmarks lm=use.lm;
            float left=dp(22),right=w-dp(22),y=dp(92);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setColor(LINE);c.drawLine(left,y,right,y,p);

            drawNode(c,left,y,"READY","",false,true);
            if(lm.splitDetected) drawNode(c,xFor(left,right,lm.splitT),y,"SPLIT","proxy",true,false);
            drawNode(c,xFor(left,right,lm.takebackT),y,"TAKEBACK","",false,false);
            drawNode(c,xFor(left,right,lm.driveT),y,"DRIVE","",false,false);
            drawNode(c,xFor(left,right,lm.peakT),y,"PEAK","",false,false);
            drawNode(c,xFor(left,right,lm.contactT),y,"CONTACT","proxy",true,false);
            drawNode(c,xFor(left,right,lm.followT),y,"FOLLOW","end",false,false);

            p.setStyle(Paint.Style.FILL);p.setTextSize(dp(12));p.setColor(TEXT);
            String chain=lm.splitDetected
                ? String.format(Locale.US,"Load → drive  %.0f ms     Load → contact  %.0f ms",lm.loadToDriveMs,lm.loadToContactMs)
                : "Split/load proxy not detected in this swing";
            c.drawText(chain,dp(16),h-dp(48),p);
            p.setTextSize(dp(11));p.setColor(MUTED);
            c.drawText(String.format(Locale.US,"Lateral preparation proxy  %.0f%%  •  timing only, not ground force",lm.lateralPrepPct),dp(16),h-dp(24),p);
        }

        float xFor(float l,float r,double t){return (float)(l+(r-l)*clamp(t,0,1));}
        void drawNode(Canvas c,float x,float y,String label,String sub,boolean proxy,boolean first){
            p.setStyle(Paint.Style.FILL);p.setColor(proxy?MUTED:ACCENT);c.drawCircle(x,y,dp(first?6:5),p);
            p.setTextSize(dp(9));p.setColor(TEXT);p.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));
            float tw=p.measureText(label);c.drawText(label,Math.max(dp(3),Math.min(getWidth()-tw-dp(3),x-tw/2)),y-dp(16),p);
            if(sub!=null&&!sub.isEmpty()){p.setTextSize(dp(8));p.setColor(MUTED);float sw=p.measureText(sub);c.drawText(sub,Math.max(dp(3),Math.min(getWidth()-sw-dp(3),x-sw/2)),y+dp(20),p);}
        }
    }

    class MotionBalanceView extends View{
        Paint p=new Paint(1);Metrics m,ref;
        MotionBalanceView(Context c){super(c);}
        void setMetrics(Metrics mm,Metrics rr){m=mm;ref=rr;invalidate();}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);int w=getWidth(),h=getHeight();
            p.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));p.setTextSize(dp(12));p.setColor(MUTED);p.setStyle(Paint.Style.FILL);
            c.drawText("STROKE INTENT",dp(16),dp(24),p);
            RectF area=new RectF(dp(18),dp(52),w-dp(18),h-dp(38));
            p.setColor(LINE);c.drawRoundRect(area,dp(10),dp(10),p);
            double f=m!=null?m.forwardPct:(ref!=null?ref.forwardPct:50);
            double u=m!=null?m.upwardPct:(ref!=null?ref.upwardPct:50);
            float split=(float)(area.left+area.width()*(f/100.0));
            p.setColor(Color.rgb(72,91,82));c.drawRoundRect(new RectF(area.left,area.top,split,area.bottom),dp(10),dp(10),p);
            p.setColor(Color.rgb(95,105,100));c.drawRoundRect(new RectF(split,area.top,area.right,area.bottom),dp(10),dp(10),p);
            p.setColor(ACCENT);c.drawCircle(split,area.centerY(),dp(7),p);
            p.setTextSize(dp(18));p.setColor(TEXT);
            c.drawText(String.format(Locale.US,"%.0f%% FORWARD",f),area.left+dp(12),area.centerY()+dp(6),p);
            String up=String.format(Locale.US,"%.0f%% UP",u);float tw=p.measureText(up);c.drawText(up,area.right-tw-dp(12),area.centerY()+dp(6),p);
            p.setTextSize(dp(11));p.setColor(MUTED);c.drawText("FORWARD  •  through / depth tendency",area.left,area.bottom+dp(22),p);
            String s="UPWARD  •  arc / spin tendency";tw=p.measureText(s);c.drawText(s,area.right-tw,area.bottom+dp(20),p);
        }
    }

    static class V{
        double x,y,z;V(){this(0,0,0);}V(double X,double Y,double Z){x=X;y=Y;z=Z;}
        V add(V o){return new V(x+o.x,y+o.y,z+o.z);}V sub(V o){return new V(x-o.x,y-o.y,z-o.z);}V mul(double s){return new V(x*s,y*s,z*s);}double mag(){return Math.sqrt(x*x+y*y+z*z);}
    }
    static class Sample{
        long t;V a,g,orient;Sample(long T,V A,V G,V O){t=T;a=A;g=G;orient=O;}
    }
    static class Landmarks{
        boolean splitDetected=false;
        double splitT=Double.NaN,takebackT=0,driveT=0,peakT=0,contactT=0,followT=1;
        double loadToDriveMs=Double.NaN,loadToContactMs=Double.NaN,lateralPrepPct=0;
    }
    static class Metrics{
        double duration,maxSpeed,maxGyro,maxAccel,pathAngle,forwardPct,upwardPct,followRatio,peakOffsetMs,smoothness;
        int impact;double[] speed;float[] traceX,traceY;Landmarks lm;
        Metrics(double a,double b,double c,double d,double e,double f,double g,double h,double i,double j,int k,double[] l,float[] x,float[] y,Landmarks lm0){
            duration=a;maxSpeed=b;maxGyro=c;maxAccel=d;pathAngle=e;forwardPct=f;upwardPct=g;followRatio=h;peakOffsetMs=i;smoothness=j;impact=k;speed=l;traceX=x;traceY=y;lm=lm0;
        }
    }

    float[] transpose(float[] a){return new float[]{a[0],a[3],a[6],a[1],a[4],a[7],a[2],a[5],a[8]};}
    float[] mulMat(float[] a,float[] b){float[] o=new float[9];for(int r=0;r<3;r++)for(int c=0;c<3;c++)o[r*3+c]=a[r*3]*b[c]+a[r*3+1]*b[3+c]+a[r*3+2]*b[6+c];return o;}
    float[] mulVec(float[] m,float[] v){return new float[]{m[0]*v[0]+m[1]*v[1]+m[2]*v[2],m[3]*v[0]+m[4]*v[1]+m[5]*v[2],m[6]*v[0]+m[7]*v[1]+m[8]*v[2]};}
    String yes(boolean v){return v?"✓":"—";} double mag(double x,double y,double z){return Math.sqrt(x*x+y*y+z*z);} static double clamp(double v,double a,double b){return Math.max(a,Math.min(b,v));}

    void pulseReady(){
        readyState.animate().cancel();
        readyState.setScaleX(1f); readyState.setScaleY(1f); readyState.setAlpha(1f);
        readyState.animate().scaleX(1.025f).scaleY(1.025f).setDuration(110).withEndAction(
            ()->readyState.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        ).start();
    }

    GradientDrawable roundRect(int color,int radiusDp,int strokeColor,int strokeDp){
        GradientDrawable g=new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radiusDp));
        if(strokeDp>0)g.setStroke(dp(strokeDp),strokeColor);
        return g;
    }

    TextView tv(String s,float sp,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setLineSpacing(0,1.15f);if(bold)v.setTypeface(v.getTypeface(),Typeface.BOLD);return v;}
    View card(View child){LinearLayout c=new LinearLayout(this);c.setPadding(dp(16),dp(15),dp(16),dp(15));c.setBackground(roundRect(SURFACE,16,LINE,1));c.addView(child);return c;}
    View chip(View child){LinearLayout c=new LinearLayout(this);c.setPadding(dp(14),dp(12),dp(14),dp(12));c.setBackground(roundRect(SURFACE,999,LINE,1));c.addView(child);return c;}
    Button button(String s,boolean primaryStyle){Button b=new Button(this);b.setText(s);b.setTextSize(15);b.setTypeface(b.getTypeface(),Typeface.BOLD);b.setMinHeight(dp(56));b.setTextColor(primaryStyle?BG:TEXT);GradientDrawable g=new GradientDrawable();g.setCornerRadius(dp(16));g.setColor(primaryStyle?ACCENT:SURFACE);g.setStroke(dp(1),primaryStyle?ACCENT:LINE);b.setBackground(g);return b;}
    Space space(int d){Space s=new Space(this);s.setLayoutParams(new LinearLayout.LayoutParams(1,dp(d)));return s;}int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+.5f);}
}
