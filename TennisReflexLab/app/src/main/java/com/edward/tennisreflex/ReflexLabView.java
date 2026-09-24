package com.edward.tennisreflex;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Random;

public class ReflexLabView extends View implements SensorEventListener {
    private enum Screen { HOME, GAME, RESULT, PROGRESS }
    private enum Game { BALANCE, PRECISION, REFLEX, SPLIT, CHAOS, RALLY }

    private final int BG=Color.rgb(11,15,20), PANEL=Color.rgb(22,28,36), PANEL2=Color.rgb(29,36,45);
    private final int TEXT=Color.rgb(241,244,247), MUTED=Color.rgb(145,155,168), LIME=Color.rgb(199,255,91);
    private final int CYAN=Color.rgb(89,214,255), ORANGE=Color.rgb(255,174,66), RED=Color.rgb(255,92,92), GREEN=Color.rgb(88,230,160);

    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SensorManager sm;
    private final Sensor accel, gyro, linear;
    private final Vibrator vibrator;
    private final ToneGenerator tone;
    private final SharedPreferences prefs;
    private final Random random=new Random();

    private Screen screen=Screen.HOME;
    private Game game=Game.BALANCE;
    private float ax,ay,az=9.81f,gx,gy,gz,linearMag,roll,pitch,calibRoll,calibPitch;
    private boolean calibrated,neutralReady=true,waitingResponse,currentGo=true,chaosReverse,dailyMode;
    private long lastGestureAt,gameStart,gameDurationMs,nextPromptAt,promptAt,promptDeadline,stableSince;
    private int currentDir=-1,hits,misses,trials,rallyStreak,rallyBest,rallyLives,chaosRound,dailyIndex,lastScore;
    private double reactionTotal;
    private float scoreAccumulator,maxObservedAccel;
    private int scoreFrames;
    private String lastMetric="",lastMetricValue="";

    private final EnumMap<Game,RectF> gameRects=new EnumMap<>(Game.class);
    private final RectF dailyRect=new RectF(),progressRect=new RectF(),calibrateRect=new RectF(),resultPrimaryRect=new RectF(),resultSecondaryRect=new RectF();

    public ReflexLabView(Context c){
        super(c); setBackgroundColor(BG); setKeepScreenOn(true);
        sm=(SensorManager)c.getSystemService(Context.SENSOR_SERVICE);
        accel=sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyro=sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        linear=sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        vibrator=(Vibrator)c.getSystemService(Context.VIBRATOR_SERVICE);
        tone=new ToneGenerator(AudioManager.STREAM_MUSIC,55);
        prefs=c.getSharedPreferences("tennis_reflex_lab",Context.MODE_PRIVATE);
        calibrated=prefs.getBoolean("calibrated",false);
        calibRoll=prefs.getFloat("calibRoll",0f); calibPitch=prefs.getFloat("calibPitch",0f);
    }

    public void startSensors(){
        if(accel!=null) sm.registerListener(this,accel,SensorManager.SENSOR_DELAY_GAME);
        if(gyro!=null) sm.registerListener(this,gyro,SensorManager.SENSOR_DELAY_GAME);
        if(linear!=null) sm.registerListener(this,linear,SensorManager.SENSOR_DELAY_GAME);
    }
    public void stopSensors(){sm.unregisterListener(this);}

    @Override public void onSensorChanged(SensorEvent e){
        if(e.sensor.getType()==Sensor.TYPE_ACCELEROMETER){
            ax=e.values[0]; ay=e.values[1]; az=e.values[2];
            roll=(float)Math.atan2(ax,az);
            pitch=(float)Math.atan2(-ay,Math.sqrt(ax*ax+az*az));
            if(linear==null){float m=(float)Math.sqrt(ax*ax+ay*ay+az*az); linearMag=Math.abs(m-9.81f);}
        } else if(e.sensor.getType()==Sensor.TYPE_GYROSCOPE){gx=e.values[0];gy=e.values[1];gz=e.values[2];}
        else if(e.sensor.getType()==Sensor.TYPE_LINEAR_ACCELERATION){
            linearMag=(float)Math.sqrt(e.values[0]*e.values[0]+e.values[1]*e.values[1]+e.values[2]*e.values[2]);
        }
        postInvalidateOnAnimation();
    }
    @Override public void onAccuracyChanged(Sensor s,int a){}

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        long now=SystemClock.elapsedRealtime();
        if(screen==Screen.HOME) drawHome(c);
        else if(screen==Screen.GAME) drawGame(c,now);
        else if(screen==Screen.RESULT) drawResult(c);
        else drawProgress(c);
        postInvalidateOnAnimation();
    }

    private void drawHome(Canvas c){
        float w=getWidth(),pad=dp(20);
        text(c,"TENNIS REFLEX LAB",pad,dp(38),dp(13),LIME,true);
        text(c,"Train what happens before the stroke.",pad,dp(66),dp(24),TEXT,true);
        text(c,"Balance • control • reaction • braking",pad,dp(90),dp(14),MUTED,false);

        int level=getLevel();
        card(c,pad,dp(112),w-pad,dp(190),PANEL);
        text(c,"LEVEL "+level,pad+dp(16),dp(141),dp(13),MUTED,true);
        text(c,getLeague(level),pad+dp(16),dp(169),dp(26),TEXT,true);
        drawProgressBar(c,pad+dp(155),dp(147),w-pad-dp(16),dp(158),getLevelProgress(),LIME);

        dailyRect.set(pad,dp(208),w-pad,dp(272)); roundRect(c,dailyRect,LIME,dp(18));
        text(c,"DAILY SIX",pad+dp(18),dp(236),dp(16),BG,true);
        text(c,"6 drills • ~6 min",pad+dp(18),dp(258),dp(13),BG,false);
        textRight(c,"START",w-pad-dp(18),dp(248),dp(14),BG,true);

        calibrateRect.set(pad,dp(286),w-pad,dp(330)); roundRect(c,calibrateRect,PANEL2,dp(14));
        text(c,calibrated?"CALIBRATED":"CALIBRATE PHONE",pad+dp(16),dp(313),dp(13),calibrated?GREEN:ORANGE,true);
        textRight(c,calibrated?"TAP TO RESET":"HOLD NATURALLY",w-pad-dp(16),dp(313),dp(11),MUTED,false);

        text(c,"DRILLS",pad,dp(366),dp(12),MUTED,true);
        float gap=dp(10),top=dp(382),cardW=(w-pad*2-gap)/2f,cardH=dp(92);
        String[] names={"BALANCE","PRECISION","REFLEX","SPLIT STEP","CHAOS","RALLY REFLEX"};
        String[] subs={"stability","fine control","reaction","first move","decision","endurance"};
        Game[] gs=Game.values();
        for(int i=0;i<gs.length;i++){
            int row=i/2,col=i%2; float l=pad+col*(cardW+gap),t=top+row*(cardH+gap);
            RectF r=new RectF(l,t,l+cardW,t+cardH); gameRects.put(gs[i],r); roundRect(c,r,PANEL,dp(16));
            text(c,String.format(Locale.US,"%02d",i+1),l+dp(14),t+dp(24),dp(11),LIME,true);
            text(c,names[i],l+dp(14),t+dp(52),dp(16),TEXT,true);
            text(c,subs[i],l+dp(14),t+dp(73),dp(12),MUTED,false);
            int best=prefs.getInt("best_"+gs[i].name(),0);
            textRight(c,best==0?"—":String.valueOf(best),r.right-dp(12),t+dp(24),dp(12),best==0?MUTED:CYAN,true);
        }

        progressRect.set(pad,top+3*(cardH+gap)+dp(2),w-pad,top+3*(cardH+gap)+dp(50));
        roundRect(c,progressRect,PANEL2,dp(14));
        text(c,"PROGRESS & BASELINE",pad+dp(16),progressRect.centerY()+dp(5),dp(13),TEXT,true);
        textRight(c,"→",w-pad-dp(18),progressRect.centerY()+dp(6),dp(20),LIME,true);
    }

    private void drawGame(Canvas c,long now){
        float w=getWidth(),pad=dp(20);
        long remaining=Math.max(0,gameDurationMs-(now-gameStart));
        text(c,"‹  "+gameTitle(game),pad,dp(38),dp(14),TEXT,true);
        textRight(c,formatTime(remaining),w-pad,dp(38),dp(14),LIME,true);
        drawProgressBar(c,pad,dp(52),w-pad,dp(58),1f-(remaining/(float)gameDurationMs),LIME);

        if(!calibrated){
            textCentered(c,"CALIBRATION REQUIRED",w/2,dp(120),dp(13),ORANGE,true);
            textCentered(c,"Hold naturally, then calibrate.",w/2,dp(148),dp(14),MUTED,false);
            calibrateRect.set(pad,dp(180),w-pad,dp(236)); roundRect(c,calibrateRect,LIME,dp(16));
            textCentered(c,"CALIBRATE",w/2,dp(215),dp(15),BG,true); return;
        }
        if(remaining<=0){finishTimedGame();return;}
        int gesture=detectGesture(now);
        switch(game){
            case BALANCE:drawBalance(c);break;
            case PRECISION:drawPrecision(c,now);break;
            case REFLEX:drawReflex(c,now,gesture);break;
            case SPLIT:drawSplit(c,now);break;
            case CHAOS:drawChaos(c,now,gesture);break;
            case RALLY:drawRally(c,now,gesture);break;
        }
    }

    private void drawBalance(Canvas c){
        float w=getWidth(),cx=w/2f,cy=dp(310),dr=roll-calibRoll,dpt=pitch-calibPitch,maxTilt=.32f;
        float ux=clamp(dr/maxTilt,-1,1),uy=clamp(dpt/maxTilt,-1,1),radius=dp(96);
        circle(c,cx,cy,radius,PANEL2);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setColor(MUTED);c.drawCircle(cx,cy,dp(34),p);p.setStyle(Paint.Style.FILL);
        circle(c,cx+ux*radius,cy+uy*radius,dp(13),LIME);
        float dist=(float)Math.sqrt(ux*ux+uy*uy),instant=clamp(1-dist,0,1); scoreAccumulator+=instant;scoreFrames++;
        int live=Math.round((scoreAccumulator/Math.max(1,scoreFrames))*100);
        textCentered(c,"KEEP IT QUIET",cx,dp(455),dp(22),TEXT,true);
        textCentered(c,"Smaller corrections score higher.",cx,dp(482),dp(13),MUTED,false);
        metric(c,dp(20),dp(525),"STABILITY",live+"/100");
        metric(c,dp(20),dp(588),"TILT",String.format(Locale.US,"%.1f°",Math.toDegrees(dist*maxTilt)));
    }

    private void drawPrecision(Canvas c,long now){
        float w=getWidth(),cx=w/2f,cy=dp(315),t=(now-gameStart)/1000f;
        float tx=cx+(float)Math.sin(t*1.15f)*dp(105),ty=cy+(float)Math.sin(t*2.3f)*dp(54);
        float ux=clamp((roll-calibRoll)/.30f,-1,1),uy=clamp((pitch-calibPitch)/.30f,-1,1);
        float bx=cx+ux*dp(120),by=cy+uy*dp(90);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setColor(PANEL2);c.drawOval(new RectF(cx-dp(110),cy-dp(58),cx+dp(110),cy+dp(58)),p);p.setStyle(Paint.Style.FILL);
        circle(c,tx,ty,dp(15),CYAN);circle(c,bx,by,dp(10),LIME);
        float err=(float)Math.hypot(tx-bx,ty-by),instant=clamp(1-err/dp(170),0,1);scoreAccumulator+=instant;scoreFrames++;
        int live=Math.round((scoreAccumulator/Math.max(1,scoreFrames))*100);
        float jitter=(float)Math.sqrt(gx*gx+gy*gy+gz*gz);
        textCentered(c,"TRACE THE BLUE TARGET",cx,dp(455),dp(20),TEXT,true);
        textCentered(c,"Smooth beats fast.",cx,dp(482),dp(13),MUTED,false);
        metric(c,dp(20),dp(525),"PRECISION",live+"/100");
        metric(c,dp(20),dp(588),"HAND JITTER",String.format(Locale.US,"%.2f rad/s",jitter));
    }

    private void drawReflex(Canvas c,long now,int gesture){
        if(!waitingResponse&&now>=nextPromptAt){currentDir=random.nextInt(4);waitingResponse=true;promptAt=now;promptDeadline=now+1500;tone.startTone(ToneGenerator.TONE_PROP_BEEP,55);}
        if(waitingResponse&&gesture>=0){trials++;long rt=now-promptAt;if(gesture==currentDir){hits++;reactionTotal+=rt;feedback(true);}else{misses++;feedback(false);}waitingResponse=false;nextPromptAt=now+500+random.nextInt(700);}
        if(waitingResponse&&now>promptDeadline){trials++;misses++;waitingResponse=false;nextPromptAt=now+500;feedback(false);}
        drawDirectionStage(c,currentDir,waitingResponse?LIME:PANEL2,"REACT","Move in the shown direction.");
        metric(c,dp(20),dp(535),"HITS",hits+" / "+trials);
        metric(c,dp(20),dp(598),"AVG REACTION",hits==0?"—":Math.round(reactionTotal/hits)+" ms");
    }

    private void drawSplit(Canvas c,long now){
        if(!waitingResponse&&now>=nextPromptAt){waitingResponse=true;promptAt=now;promptDeadline=now+1800;tone.startTone(ToneGenerator.TONE_PROP_BEEP,80);}
        if(waitingResponse&&linearMag>1.8f&&now-promptAt>60){long rt=now-promptAt;trials++;hits++;reactionTotal+=rt;maxObservedAccel=Math.max(maxObservedAccel,linearMag);waitingResponse=false;nextPromptAt=now+900+random.nextInt(1300);feedback(true);}
        else if(waitingResponse&&now>promptDeadline){trials++;misses++;waitingResponse=false;nextPromptAt=now+700;feedback(false);}
        float w=getWidth();String s=waitingResponse?"GO":"READY";int color=waitingResponse?LIME:MUTED;
        textCentered(c,s,w/2f,dp(290),dp(72),color,true);
        textCentered(c,waitingResponse?"Explode on the beep.":"Stay loose. Wait for it.",w/2f,dp(342),dp(14),MUTED,false);
        circle(c,w/2f,dp(420),dp(42)+Math.min(dp(28),linearMag*dp(8)),waitingResponse?LIME:PANEL2);
        metric(c,dp(20),dp(535),"AVG START",hits==0?"—":Math.round(reactionTotal/hits)+" ms");
        metric(c,dp(20),dp(598),"PEAK ACCEL",String.format(Locale.US,"%.1f m/s²",maxObservedAccel));
    }

    private void drawChaos(Canvas c,long now,int gesture){
        if(!waitingResponse&&now>=nextPromptAt){
            chaosRound++; if(chaosRound%5==0) chaosReverse=!chaosReverse;
            currentDir=random.nextInt(4);currentGo=random.nextFloat()>.28f;waitingResponse=true;promptAt=now;promptDeadline=now+900;
        }
        if(waitingResponse){
            if(currentGo&&gesture>=0){trials++;boolean ok=gesture==effectiveChaosDir();if(ok){hits++;reactionTotal+=now-promptAt;feedback(true);}else{misses++;feedback(false);}waitingResponse=false;nextPromptAt=now+350;}
            else if(!currentGo&&gesture>=0){trials++;misses++;waitingResponse=false;nextPromptAt=now+350;feedback(false);}
            else if(now>promptDeadline){trials++;if(!currentGo){hits++;feedback(true);}else{misses++;feedback(false);}waitingResponse=false;nextPromptAt=now+350;}
        }
        float w=getWidth();int color=currentGo?(chaosReverse?ORANGE:LIME):RED;
        textCentered(c,chaosReverse?"RULE: REVERSE":"RULE: NORMAL",w/2f,dp(126),dp(14),chaosReverse?ORANGE:MUTED,true);
        textCentered(c,waitingResponse?(currentGo?dirSymbol(effectiveChaosDir()):"✕"):"·",w/2f,dp(310),dp(86),waitingResponse?color:PANEL2,true);
        textCentered(c,waitingResponse?(currentGo?"MOVE":"HOLD"):"READ THE NEXT CUE",w/2f,dp(365),dp(16),waitingResponse?color:MUTED,true);
        metric(c,dp(20),dp(535),"DECISIONS",hits+" / "+trials);
        metric(c,dp(20),dp(598),"ACCURACY",trials==0?"—":Math.round(100f*hits/trials)+"%");
    }

    private void drawRally(Canvas c,long now,int gesture){
        if(!waitingResponse&&now>=nextPromptAt){currentDir=random.nextInt(5);waitingResponse=true;promptAt=now;promptDeadline=now+Math.max(520,1300-hits*22L);stableSince=0;tone.startTone(ToneGenerator.TONE_PROP_BEEP2,40);}
        if(waitingResponse){
            boolean answered=false,correct=false;
            if(currentDir==4){
                float dr=Math.abs(roll-calibRoll),dpt=Math.abs(pitch-calibPitch);
                if(dr<.07f&&dpt<.07f){if(stableSince==0)stableSince=now;if(now-stableSince>260){answered=true;correct=true;}} else stableSince=0;
                if(gesture>=0){answered=true;correct=false;}
            } else if(gesture>=0){answered=true;correct=gesture==currentDir;}
            if(answered){trials++;if(correct){hits++;rallyStreak++;rallyBest=Math.max(rallyBest,rallyStreak);reactionTotal+=now-promptAt;feedback(true);}else{misses++;rallyLives--;rallyStreak=0;feedback(false);}waitingResponse=false;nextPromptAt=now+180;stableSince=0;}
            else if(now>promptDeadline){trials++;misses++;rallyLives--;rallyStreak=0;waitingResponse=false;nextPromptAt=now+180;stableSince=0;feedback(false);}
        }
        if(rallyLives<=0){finishGame(clampInt(rallyBest*4+hits*2,0,100),"BEST RALLY",rallyBest+" shots");return;}
        float w=getWidth();
        textCentered(c,"RALLY "+rallyStreak,w/2f,dp(130),dp(18),CYAN,true);
        textCentered(c,waitingResponse?rallyCommand(currentDir):"READY",w/2f,dp(300),dp(48),waitingResponse?LIME:MUTED,true);
        textCentered(c,waitingResponse?rallyHint(currentDir):"Read • move • recover",w/2f,dp(340),dp(14),MUTED,false);
        textCentered(c,"LIVES  "+livesString(rallyLives),w/2f,dp(430),dp(15),rallyLives==1?RED:TEXT,true);
        metric(c,dp(20),dp(535),"BEST RALLY",rallyBest+" shots");
        metric(c,dp(20),dp(598),"AVG REACTION",hits==0?"—":Math.round(reactionTotal/hits)+" ms");
    }

    private void drawDirectionStage(Canvas c,int dir,int color,String title,String sub){
        float w=getWidth();
        textCentered(c,title,w/2f,dp(130),dp(14),MUTED,true);
        textCentered(c,waitingResponse?dirSymbol(dir):"·",w/2f,dp(300),dp(94),color,true);
        textCentered(c,sub,w/2f,dp(382),dp(14),MUTED,false);
    }

    private void drawResult(Canvas c){
        float w=getWidth(),pad=dp(20);
        text(c,"SESSION COMPLETE",pad,dp(42),dp(13),LIME,true);
        text(c,gameTitle(game),pad,dp(78),dp(27),TEXT,true);
        textCentered(c,String.valueOf(lastScore),w/2f,dp(238),dp(92),TEXT,true);
        textCentered(c,"SCORE / 100",w/2f,dp(276),dp(13),MUTED,true);
        card(c,pad,dp(318),w-pad,dp(402),PANEL);
        text(c,lastMetric,pad+dp(18),dp(348),dp(12),MUTED,true);
        text(c,lastMetricValue,pad+dp(18),dp(382),dp(25),CYAN,true);
        textRight(c,"BEST  "+prefs.getInt("best_"+game.name(),0),w-pad-dp(18),dp(374),dp(12),MUTED,true);

        resultPrimaryRect.set(pad,dp(444),w-pad,dp(506));roundRect(c,resultPrimaryRect,LIME,dp(17));
        String primary=dailyMode&&dailyIndex<Game.values().length-1?"NEXT DRILL":"BACK TO LAB";
        textCentered(c,primary,w/2f,dp(482),dp(15),BG,true);
        resultSecondaryRect.set(pad,dp(520),w-pad,dp(574));roundRect(c,resultSecondaryRect,PANEL2,dp(16));
        textCentered(c,"RETRY",w/2f,dp(554),dp(14),TEXT,true);
        textCentered(c,"+"+(50+lastScore)+" XP",w/2f,dp(624),dp(13),LIME,true);
    }

    private void drawProgress(Canvas c){
        float w=getWidth(),pad=dp(20);text(c,"‹  PROGRESS",pad,dp(42),dp(14),TEXT,true);
        int level=getLevel();text(c,getLeague(level),pad,dp(92),dp(30),TEXT,true);textRight(c,"LEVEL "+level,w-pad,dp(92),dp(13),LIME,true);
        drawProgressBar(c,pad,dp(112),w-pad,dp(120),getLevelProgress(),LIME);
        text(c,"PERSONAL BASELINE",pad,dp(164),dp(12),MUTED,true);
        float y=dp(184);
        for(Game g:Game.values()){
            RectF r=new RectF(pad,y,w-pad,y+dp(66));roundRect(c,r,PANEL,dp(14));
            text(c,gameTitle(g),pad+dp(14),y+dp(27),dp(13),TEXT,true);
            int last=prefs.getInt("last_"+g.name(),0),best=prefs.getInt("best_"+g.name(),0);
            text(c,"last "+(last==0?"—":last),pad+dp(14),y+dp(49),dp(11),MUTED,false);
            textRight(c,best==0?"—":best+"/100",w-pad-dp(14),y+dp(40),dp(17),best==0?MUTED:CYAN,true); y+=dp(76);
        }
        textCentered(c,"All data stays on this phone.",w/2f,y+dp(28),dp(12),MUTED,false);
    }

    private void startGame(Game g){
        game=g;screen=Screen.GAME;gameStart=SystemClock.elapsedRealtime();
        gameDurationMs=(g==Game.BALANCE||g==Game.PRECISION)?30000:(g==Game.RALLY?60000:45000);
        nextPromptAt=gameStart+900;promptAt=promptDeadline=0;currentDir=-1;waitingResponse=false;
        hits=misses=trials=0;reactionTotal=0;scoreAccumulator=0;scoreFrames=0;maxObservedAccel=0;rallyStreak=rallyBest=0;rallyLives=3;chaosRound=0;chaosReverse=false;stableSince=0;neutralReady=true;lastGestureAt=0;feedbackPulse(18);
    }

    private void finishTimedGame(){
        int score;String metric,value;
        switch(game){
            case BALANCE:score=Math.round((scoreAccumulator/Math.max(1,scoreFrames))*100);metric="STABILITY";value=score+"/100";break;
            case PRECISION:score=Math.round((scoreAccumulator/Math.max(1,scoreFrames))*100);metric="PRECISION";value=score+"/100";break;
            case REFLEX:score=reactionScore();metric="AVG REACTION";value=hits==0?"—":Math.round(reactionTotal/hits)+" ms";break;
            case SPLIT:score=reactionScore();metric="AVG START";value=hits==0?"—":Math.round(reactionTotal/hits)+" ms";break;
            case CHAOS:score=accuracyReactionScore();metric="DECISION ACCURACY";value=trials==0?"—":Math.round(100f*hits/trials)+"%";break;
            default:score=clampInt(rallyBest*4+hits*2,0,100);metric="BEST RALLY";value=rallyBest+" shots";
        }
        finishGame(score,metric,value);
    }

    private void finishGame(int score,String metric,String value){
        if(screen==Screen.RESULT)return;
        lastScore=clampInt(score,0,100);lastMetric=metric;lastMetricValue=value;
        int best=Math.max(lastScore,prefs.getInt("best_"+game.name(),0)),xp=prefs.getInt("xp",0)+50+lastScore;
        prefs.edit().putInt("last_"+game.name(),lastScore).putInt("best_"+game.name(),best).putInt("xp",xp).apply();
        screen=Screen.RESULT;tone.startTone(ToneGenerator.TONE_PROP_ACK,120);feedbackPulse(45);
    }

    private int detectGesture(long now){
        float dr=roll-calibRoll,dpt=pitch-calibPitch;
        if(Math.abs(dr)<.075f&&Math.abs(dpt)<.075f)neutralReady=true;
        if(!neutralReady||now-lastGestureAt<180)return-1;
        int dir=-1;float th=.16f;
        if(Math.abs(dr)>Math.abs(dpt)&&Math.abs(dr)>th)dir=dr<0?0:1;
        else if(Math.abs(dpt)>th)dir=dpt<0?2:3;
        if(dir>=0){neutralReady=false;lastGestureAt=now;}return dir;
    }
    private int effectiveChaosDir(){if(!chaosReverse)return currentDir;if(currentDir==0)return 1;if(currentDir==1)return 0;if(currentDir==2)return 3;return 2;}
    private int reactionScore(){if(trials==0||hits==0)return 0;float accuracy=hits/(float)trials,avg=(float)(reactionTotal/hits),speed=clamp((700-avg)/450,0,1);return Math.round(100*(.55f*accuracy+.45f*speed));}
    private int accuracyReactionScore(){if(trials==0)return 0;float accuracy=hits/(float)trials,avg=hits==0?900:(float)(reactionTotal/hits),speed=clamp((800-avg)/520,0,1);return Math.round(100*(.7f*accuracy+.3f*speed));}

    private void calibrate(){calibRoll=roll;calibPitch=pitch;calibrated=true;prefs.edit().putBoolean("calibrated",true).putFloat("calibRoll",calibRoll).putFloat("calibPitch",calibPitch).apply();feedback(true);}

    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getAction()!=MotionEvent.ACTION_UP)return true;float x=e.getX(),y=e.getY();
        if(screen==Screen.HOME){
            if(dailyRect.contains(x,y)){dailyMode=true;dailyIndex=0;startGame(Game.values()[0]);return true;}
            if(calibrateRect.contains(x,y)){calibrate();return true;}
            if(progressRect.contains(x,y)){screen=Screen.PROGRESS;return true;}
            for(Game g:Game.values()){RectF r=gameRects.get(g);if(r!=null&&r.contains(x,y)){dailyMode=false;startGame(g);return true;}}
        } else if(screen==Screen.GAME){
            if(y<dp(72)){screen=Screen.HOME;dailyMode=false;return true;}
            if(!calibrated&&calibrateRect.contains(x,y)){calibrate();startGame(game);return true;}
        } else if(screen==Screen.RESULT){
            if(resultPrimaryRect.contains(x,y)){if(dailyMode&&dailyIndex<Game.values().length-1){dailyIndex++;startGame(Game.values()[dailyIndex]);}else{dailyMode=false;screen=Screen.HOME;}return true;}
            if(resultSecondaryRect.contains(x,y)){startGame(game);return true;}
        } else if(screen==Screen.PROGRESS&&y<dp(80)){screen=Screen.HOME;return true;}
        return true;
    }

    private void feedback(boolean ok){feedbackPulse(ok?24:65);tone.startTone(ok?ToneGenerator.TONE_PROP_ACK:ToneGenerator.TONE_PROP_NACK,ok?45:80);}
    private void feedbackPulse(long ms){if(vibrator!=null&&vibrator.hasVibrator())vibrator.vibrate(VibrationEffect.createOneShot(ms,VibrationEffect.DEFAULT_AMPLITUDE));}
    private int getLevel(){return Math.min(50,1+prefs.getInt("xp",0)/400);}
    private float getLevelProgress(){return(prefs.getInt("xp",0)%400)/400f;}
    private String getLeague(int l){if(l<=10)return"FOUNDATION";if(l<=20)return"CONTROL";if(l<=30)return"REACTION";if(l<=40)return"MOVEMENT";return"TENNIS IQ";}
    private String gameTitle(Game g){switch(g){case BALANCE:return"BALANCE";case PRECISION:return"PRECISION";case REFLEX:return"REFLEX";case SPLIT:return"SPLIT STEP";case CHAOS:return"CHAOS";default:return"RALLY REFLEX";}}
    private String dirSymbol(int d){switch(d){case 0:return"←";case 1:return"→";case 2:return"↑";case 3:return"↓";default:return"·";}}
    private String rallyCommand(int d){switch(d){case 0:return"LEFT";case 1:return"RIGHT";case 2:return"SHORT";case 3:return"DEEP";default:return"RECOVER";}}
    private String rallyHint(int d){switch(d){case 0:return"tilt left";case 1:return"tilt right";case 2:return"drive forward";case 3:return"drop back";default:return"return to neutral";}}
    private String livesString(int n){StringBuilder b=new StringBuilder();for(int i=0;i<3;i++)b.append(i<n?"● ":"○ ");return b.toString().trim();}
    private String formatTime(long ms){long s=Math.max(0,(ms+999)/1000);return String.format(Locale.US,"0:%02d",s);}

    private void metric(Canvas c,float x,float y,String label,String value){RectF r=new RectF(x,y,getWidth()-x,y+dp(50));roundRect(c,r,PANEL,dp(13));text(c,label,x+dp(14),y+dp(30),dp(11),MUTED,true);textRight(c,value,r.right-dp(14),y+dp(31),dp(16),TEXT,true);}
    private void drawProgressBar(Canvas c,float l,float t,float r,float b,float progress,int color){roundRect(c,new RectF(l,t,r,b),PANEL2,dp(4));roundRect(c,new RectF(l,t,l+(r-l)*clamp(progress,0,1),b),color,dp(4));}
    private void card(Canvas c,float l,float t,float r,float b,int color){roundRect(c,new RectF(l,t,r,b),color,dp(18));}
    private void roundRect(Canvas c,RectF r,int color,float radius){p.setStyle(Paint.Style.FILL);p.setColor(color);c.drawRoundRect(r,radius,radius,p);}
    private void circle(Canvas c,float x,float y,float radius,int color){p.setStyle(Paint.Style.FILL);p.setColor(color);c.drawCircle(x,y,radius,p);}
    private void text(Canvas c,String s,float x,float y,float size,int color,boolean bold){p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextSize(size);p.setTextAlign(Paint.Align.LEFT);p.setTypeface(android.graphics.Typeface.create("sans-serif",bold?1:0));c.drawText(s,x,y,p);}
    private void textRight(Canvas c,String s,float x,float y,float size,int color,boolean bold){p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextSize(size);p.setTextAlign(Paint.Align.RIGHT);p.setTypeface(android.graphics.Typeface.create("sans-serif",bold?1:0));c.drawText(s,x,y,p);}
    private void textCentered(Canvas c,String s,float x,float y,float size,int color,boolean bold){p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextSize(size);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(android.graphics.Typeface.create("sans-serif",bold?1:0));c.drawText(s,x,y,p);}
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
    private float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private int clampInt(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
}
