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
    private enum Screen { HOME, COURT_LAB, GAME, RESULT, PROGRESS }
    private enum Game { BALANCE, PRECISION, REFLEX, SPLIT, CHAOS, RALLY, RECOVER, ANTICIPATION, TWO_SHOT }

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
    private float ax,ay,az=9.81f,gx,gy,gz,linearMag,roll,pitch,filteredRoll,filteredPitch,calibRoll,calibPitch;
    private float balanceX,balanceY,precisionX,precisionY;
    private float rallyPlayerX,rallyPlayerY,rallyTargetX,rallyTargetY;
    private long rallyBallStart,rallyTravelMs;
    private boolean rallyBallActive;
    private boolean courtRecovering;
    private long courtRecoverStart;
    private int twoShotStep;
    private float lastCourtTargetX,lastCourtTargetY;
    private boolean sensorFilterReady;
    private boolean calibrated,neutralReady=true,waitingResponse,currentGo=true,chaosReverse,dailyMode;
    private boolean sessionCalibrating;
    private boolean motionMapActive,motionMapReady;
    private int motionMapStage;
    private float axisSignX=1f,axisSignY=1f;
    private long motionMapNeutralSince;
    private long autoCalibStart,autoStableSince;
    private float autoRollSum,autoPitchSum;
    private int autoCalibSamples;
    private long lastGestureAt,gameStart,gameDurationMs,nextPromptAt,promptAt,promptDeadline,stableSince;
    private int currentDir=-1,hits,misses,trials,rallyStreak,rallyBest,rallyLives,chaosRound,dailyIndex,lastScore;
    private double reactionTotal;
    private float scoreAccumulator,maxObservedAccel;
    private int scoreFrames;
    private String lastMetric="",lastMetricValue="";

    private final EnumMap<Game,RectF> gameRects=new EnumMap<>(Game.class);
    private final EnumMap<Game,RectF> courtGameRects=new EnumMap<>(Game.class);
    private final RectF dailyRect=new RectF(),progressRect=new RectF(),calibrateRect=new RectF(),courtLabRect=new RectF(),courtProgressRect=new RectF(),resultPrimaryRect=new RectF(),resultSecondaryRect=new RectF();

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
        motionMapReady=prefs.getBoolean("motionMapReady",false);
        axisSignX=prefs.getFloat("axisSignX",1f);
        axisSignY=prefs.getFloat("axisSignY",1f);
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

            // Heavy low-pass filtering for fine motor-control drills.
            // Raw accelerometer tilt is intentionally NOT mapped directly to the ball.
            if(!sensorFilterReady){
                filteredRoll=roll;
                filteredPitch=pitch;
                sensorFilterReady=true;
            } else {
                final float sensorAlpha=0.085f;
                filteredRoll += (roll-filteredRoll)*sensorAlpha;
                filteredPitch += (pitch-filteredPitch)*sensorAlpha;
            }

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
        else if(screen==Screen.COURT_LAB) drawCourtLab(c);
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
        text(c,"MOTION MAP",pad+dp(16),dp(313),dp(13),motionMapReady?GREEN:ORANGE,true);
        textRight(c,motionMapReady?"LEARNED • TAP TO RESET":"AUTO SETUP",w-pad-dp(16),dp(313),dp(11),MUTED,false);

        text(c,"DRILLS",pad,dp(366),dp(12),MUTED,true);
        float gap=dp(10),top=dp(382),cardW=(w-pad*2-gap)/2f,cardH=dp(92);
        String[] names={"BALANCE","PRECISION","REFLEX","SPLIT STEP","CHAOS","RALLY REFLEX"};
        String[] subs={"stability","fine control","reaction","first move","decision","endurance"};
        Game[] gs={Game.BALANCE,Game.PRECISION,Game.REFLEX,Game.SPLIT,Game.CHAOS,Game.RALLY};
        for(int i=0;i<gs.length;i++){
            int row=i/2,col=i%2; float l=pad+col*(cardW+gap),t=top+row*(cardH+gap);
            RectF r=new RectF(l,t,l+cardW,t+cardH); gameRects.put(gs[i],r); roundRect(c,r,PANEL,dp(16));
            text(c,String.format(Locale.US,"%02d",i+1),l+dp(14),t+dp(24),dp(11),LIME,true);
            text(c,names[i],l+dp(14),t+dp(52),dp(16),TEXT,true);
            text(c,subs[i],l+dp(14),t+dp(73),dp(12),MUTED,false);
            int best=prefs.getInt("best_"+gs[i].name(),0);
            textRight(c,best==0?"—":String.valueOf(best),r.right-dp(12),t+dp(24),dp(12),best==0?MUTED:CYAN,true);
        }

        courtLabRect.set(pad,top+3*(cardH+gap)+dp(2),w-pad,top+3*(cardH+gap)+dp(58));
        roundRect(c,courtLabRect,PANEL2,dp(14));
        text(c,"COURT LAB",pad+dp(16),courtLabRect.centerY()-dp(2),dp(14),TEXT,true);
        text(c,"3 new visual drills",pad+dp(16),courtLabRect.centerY()+dp(18),dp(11),MUTED,false);
        textRight(c,"→",w-pad-dp(18),courtLabRect.centerY()+dp(7),dp(20),LIME,true);
    }

    private void drawCourtLab(Canvas c){
        float w=getWidth(),pad=dp(20);
        text(c,"‹  COURT LAB",pad,dp(42),dp(14),TEXT,true);
        text(c,"Read the ball. Move. Recover.",pad,dp(82),dp(25),TEXT,true);
        text(c,"Visual tennis drills driven by phone motion.",pad,dp(108),dp(13),MUTED,false);

        Game[] modes={Game.RECOVER,Game.ANTICIPATION,Game.TWO_SHOT};
        String[] names={"RECOVER","ANTICIPATION","TWO SHOT"};
        String[] subs={"intercept → return to center","read trajectory before target appears","survive two-ball combinations"};
        float y=dp(146);
        for(int i=0;i<modes.length;i++){
            RectF r=new RectF(pad,y,w-pad,y+dp(110));
            courtGameRects.put(modes[i],r);
            roundRect(c,r,PANEL,dp(18));
            text(c,String.format(Locale.US,"%02d",i+1),pad+dp(16),y+dp(28),dp(11),LIME,true);
            text(c,names[i],pad+dp(16),y+dp(59),dp(19),TEXT,true);
            text(c,subs[i],pad+dp(16),y+dp(84),dp(12),MUTED,false);
            int best=prefs.getInt("best_"+modes[i].name(),0);
            textRight(c,best==0?"—":best+"/100",w-pad-dp(16),y+dp(59),dp(14),best==0?MUTED:CYAN,true);
            y+=dp(124);
        }

        courtProgressRect.set(pad,y+dp(8),w-pad,y+dp(60));
        roundRect(c,courtProgressRect,PANEL2,dp(14));
        text(c,"PROGRESS & BASELINE",pad+dp(16),courtProgressRect.centerY()+dp(5),dp(13),TEXT,true);
        textRight(c,"→",w-pad-dp(18),courtProgressRect.centerY()+dp(6),dp(20),LIME,true);
    }

    private void drawGame(Canvas c,long now){
        float w=getWidth(),pad=dp(20);
        long remaining=sessionCalibrating?gameDurationMs:Math.max(0,gameDurationMs-(now-gameStart));
        text(c,"‹  "+gameTitle(game),pad,dp(38),dp(14),TEXT,true);
        textRight(c,sessionCalibrating?"CAL":formatTime(remaining),w-pad,dp(38),dp(14),sessionCalibrating?ORANGE:LIME,true);
        drawProgressBar(c,pad,dp(52),w-pad,dp(58),sessionCalibrating?0f:1f-(remaining/(float)gameDurationMs),LIME);

        if(sessionCalibrating){
            drawAutoCalibration(c,now);
            return;
        }
        if(motionMapActive){
            drawMotionMap(c,now);
            return;
        }
        if(remaining<=0){finishTimedGame();return;}
        int gesture=detectGesture(now);
        switch(game){
            case BALANCE:drawBalance(c);break;
            case PRECISION:drawPrecision(c,now);break;
            case REFLEX:drawReflex(c,now,gesture);break;
            case SPLIT:drawSplit(c,now);break;
            case CHAOS:drawChaos(c,now,gesture);break;
            case RALLY:drawRally(c,now);break;
            case RECOVER:drawRecover(c,now);break;
            case ANTICIPATION:drawAnticipation(c,now);break;
            case TWO_SHOT:drawTwoShot(c,now);break;
        }
    }

    private void drawBalance(Canvas c){
        float w=getWidth(),cx=w/2f,cy=dp(310);
        float dr=mappedRollDelta(),dpt=mappedPitchDelta();
        final float deadZone=.035f;   // ~2 degrees
        final float maxTilt=.50f;     // ~28.6 degrees for full travel

        float targetX=softTilt(dr,deadZone,maxTilt);
        float targetY=softTilt(dpt,deadZone,maxTilt);

        // Visual damping: the ball follows the filtered target instead of snapping to it.
        final float follow=.075f;
        balanceX += (targetX-balanceX)*follow;
        balanceY += (targetY-balanceY)*follow;

        float radius=dp(96);
        circle(c,cx,cy,radius,PANEL2);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setColor(MUTED);c.drawCircle(cx,cy,dp(34),p);p.setStyle(Paint.Style.FILL);
        circle(c,cx+balanceX*radius,cy+balanceY*radius,dp(13),LIME);

        float dist=clamp((float)Math.sqrt(balanceX*balanceX+balanceY*balanceY),0,1);
        float instant=clamp(1-dist,0,1); scoreAccumulator+=instant;scoreFrames++;
        int live=Math.round((scoreAccumulator/Math.max(1,scoreFrames))*100);
        float tiltDeg=(float)Math.toDegrees(Math.sqrt(dr*dr+dpt*dpt));

        textCentered(c,"KEEP IT QUIET",cx,dp(455),dp(22),TEXT,true);
        textCentered(c,"Fine tilt • damped response • 2° dead zone",cx,dp(482),dp(13),MUTED,false);
        metric(c,dp(20),dp(525),"STABILITY",live+"/100");
        metric(c,dp(20),dp(588),"TILT",String.format(Locale.US,"%.1f°",tiltDeg));
    }

    private void drawPrecision(Canvas c,long now){
        float w=getWidth(),cx=w/2f,cy=dp(315),t=(now-gameStart)/1000f;
        float tx=cx+(float)Math.sin(t*1.15f)*dp(105),ty=cy+(float)Math.sin(t*2.3f)*dp(54);
        float targetX=softTilt(mappedRollDelta(),.025f,.42f);
        float targetY=softTilt(mappedPitchDelta(),.025f,.42f);
        precisionX += (targetX-precisionX)*.11f;
        precisionY += (targetY-precisionY)*.11f;
        float bx=cx+precisionX*dp(120),by=cy+precisionY*dp(90);
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

    private void drawRally(Canvas c,long now){
        float w=getWidth();
        float left=dp(34),right=w-dp(34),top=dp(92),bottom=dp(500);
        float netY=dp(280);
        float courtW=right-left;

        // Draw a simple top-down tennis court.
        roundRect(c,new RectF(left,top,right,bottom),PANEL,dp(8));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(MUTED);
        c.drawRect(left,top,right,bottom,p);
        c.drawLine(left,netY,right,netY,p);
        c.drawLine(w/2f,netY,w/2f,bottom,p);
        c.drawLine(left,dp(395),right,dp(395),p);
        p.setStyle(Paint.Style.FILL);

        // The player's green marker follows the phone continuously.
        float targetPlayerX=softTilt(mappedRollDelta(),.025f,.42f);
        float targetPlayerY=softTilt(mappedPitchDelta(),.025f,.42f);
        rallyPlayerX += (targetPlayerX-rallyPlayerX)*.13f;
        rallyPlayerY += (targetPlayerY-rallyPlayerY)*.13f;

        float playerX=w/2f+rallyPlayerX*(courtW*.42f);
        float playerY=dp(390)+rallyPlayerY*dp(88);
        playerX=clamp(playerX,left+dp(18),right-dp(18));
        playerY=clamp(playerY,netY+dp(28),bottom-dp(20));

        // Spawn the next incoming ball after a short recovery beat.
        if(!rallyBallActive && now>=nextPromptAt){
            spawnRallyBall(now);
        }

        if(rallyBallActive){
            float tx=w/2f+rallyTargetX*(courtW*.40f);
            float ty=netY+dp(42)+rallyTargetY*dp(155);

            // Landing zone: this is the only thing the player needs to understand.
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(4));
            p.setColor(CYAN);
            c.drawCircle(tx,ty,dp(34),p);
            p.setStyle(Paint.Style.FILL);
            circle(c,tx,ty,dp(4),CYAN);

            float progress=clamp((now-rallyBallStart)/(float)rallyTravelMs,0f,1f);
            // Ease-in makes the ball feel like it is accelerating toward the player.
            float eased=progress*progress*(3f-2f*progress);
            float ballX=w/2f+(tx-w/2f)*eased;
            float ballY=top+dp(22)+(ty-(top+dp(22)))*eased;
            float ballRadius=dp(7)+dp(5)*progress;
            circle(c,ballX,ballY,ballRadius,ORANGE);

            // Small time arc around the landing zone.
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(3));
            p.setColor(LIME);
            RectF arc=new RectF(tx-dp(43),ty-dp(43),tx+dp(43),ty+dp(43));
            c.drawArc(arc,-90,360f*(1f-progress),false,p);
            p.setStyle(Paint.Style.FILL);

            if(progress>=1f){
                trials++;
                float distance=(float)Math.hypot(playerX-tx,playerY-ty);
                boolean hit=distance<=dp(48);
                if(hit){
                    hits++;
                    rallyStreak++;
                    rallyBest=Math.max(rallyBest,rallyStreak);
                    reactionTotal+=rallyTravelMs;
                    feedback(true);
                }else{
                    misses++;
                    rallyLives--;
                    rallyStreak=0;
                    feedback(false);
                }
                rallyBallActive=false;
                nextPromptAt=now+320;
            }
        }

        // Player marker always remains visible.
        circle(c,playerX,playerY,dp(17),LIME);
        circle(c,playerX,playerY,dp(6),BG);

        if(rallyLives<=0){
            finishGame(clampInt(rallyBest*6+hits*2,0,100),"BEST RALLY",rallyBest+" balls");
            return;
        }

        textCentered(c,"MOVE GREEN → LANDING CIRCLE",w/2f,dp(535),dp(13),TEXT,true);
        textCentered(c,"Tilt the phone. Be inside the ring when the ball lands.",w/2f,dp(558),dp(11),MUTED,false);
        metric(c,dp(20),dp(584),"RALLY",rallyStreak+"   •   BEST "+rallyBest);
        textCentered(c,"LIVES  "+livesString(rallyLives),w/2f,dp(656),dp(13),rallyLives==1?RED:MUTED,true);
    }

    private void spawnRallyBall(long now){
        // Three horizontal lanes and two depths create six readable landing zones.
        int lane=random.nextInt(3);
        int depth=random.nextInt(2);
        rallyTargetX=lane==0?-.78f:(lane==1?0f:.78f);
        rallyTargetY=depth==0?.18f:.78f;

        rallyBallStart=now;
        // Starts forgiving, then becomes faster as the rally survives.
        rallyTravelMs=Math.max(700,1550-rallyBest*55L);
        rallyBallActive=true;
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2,35);
    }

    private void drawRecover(Canvas c,long now){
        CourtFrame cf=drawCourtFrame(c);
        updateCourtPlayer();

        float playerX=cf.cx+rallyPlayerX*(cf.width*.42f);
        float playerY=dp(390)+rallyPlayerY*dp(88);
        playerX=clamp(playerX,cf.left+dp(18),cf.right-dp(18));
        playerY=clamp(playerY,cf.netY+dp(28),cf.bottom-dp(20));

        if(courtRecovering){
            float centerX=cf.cx, centerY=dp(438);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(4)); p.setColor(LIME);
            c.drawCircle(centerX,centerY,dp(36),p); p.setStyle(Paint.Style.FILL);

            float dist=(float)Math.hypot(playerX-centerX,playerY-centerY);
            if(dist<=dp(38)){
                hits++; rallyStreak++; rallyBest=Math.max(rallyBest,rallyStreak);
                courtRecovering=false; nextPromptAt=now+220; feedback(true);
            } else if(now-courtRecoverStart>1300){
                misses++; rallyLives--; rallyStreak=0; courtRecovering=false; nextPromptAt=now+300; feedback(false);
            }
        } else {
            if(!rallyBallActive && now>=nextPromptAt) spawnCourtBall(now,1450);
            if(rallyBallActive){
                float[] target=drawIncomingBall(c,cf,now,true,0f);
                if(target[2]>=1f){
                    trials++;
                    boolean hit=(float)Math.hypot(playerX-target[0],playerY-target[1])<=dp(48);
                    rallyBallActive=false;
                    if(hit){
                        courtRecovering=true; courtRecoverStart=now; feedbackPulse(18);
                    } else {
                        misses++; rallyLives--; rallyStreak=0; nextPromptAt=now+320; feedback(false);
                    }
                }
            }
        }

        drawCourtPlayer(c,playerX,playerY);
        if(rallyLives<=0){finishGame(clampInt(rallyBest*8+hits*3,0,100),"BEST RECOVERY",rallyBest+" cycles");return;}
        textCentered(c,courtRecovering?"RETURN TO CENTER":"INTERCEPT THE BALL",cf.cx,dp(535),dp(13),TEXT,true);
        textCentered(c,courtRecovering?"Get back before the next shot.":"Reach the landing circle before the bounce.",cf.cx,dp(558),dp(11),MUTED,false);
        metric(c,dp(20),dp(584),"RECOVERY STREAK",rallyStreak+"   •   BEST "+rallyBest);
        textCentered(c,"LIVES  "+livesString(rallyLives),cf.cx,dp(656),dp(13),rallyLives==1?RED:MUTED,true);
    }

    private void drawAnticipation(Canvas c,long now){
        CourtFrame cf=drawCourtFrame(c);
        updateCourtPlayer();
        float playerX=cf.cx+rallyPlayerX*(cf.width*.42f);
        float playerY=dp(390)+rallyPlayerY*dp(88);
        playerX=clamp(playerX,cf.left+dp(18),cf.right-dp(18));
        playerY=clamp(playerY,cf.netY+dp(28),cf.bottom-dp(20));

        if(!rallyBallActive && now>=nextPromptAt) spawnCourtBall(now,Math.max(720,1350-rallyBest*45L));
        if(rallyBallActive){
            float progress=clamp((now-rallyBallStart)/(float)rallyTravelMs,0f,1f);
            // Landing ring appears only late. Early movement must come from reading ball flight.
            boolean reveal=progress>.58f;
            float[] target=drawIncomingBall(c,cf,now,reveal,.58f);
            if(target[2]>=1f){
                trials++;
                boolean hit=(float)Math.hypot(playerX-target[0],playerY-target[1])<=dp(48);
                if(hit){hits++;rallyStreak++;rallyBest=Math.max(rallyBest,rallyStreak);feedback(true);}
                else{misses++;rallyLives--;rallyStreak=0;feedback(false);}
                rallyBallActive=false;nextPromptAt=now+260;
            }
        }

        drawCourtPlayer(c,playerX,playerY);
        if(rallyLives<=0){finishGame(clampInt(rallyBest*7+hits*2,0,100),"BEST READ",rallyBest+" balls");return;}
        textCentered(c,"READ THE FLIGHT",cf.cx,dp(535),dp(13),TEXT,true);
        textCentered(c,"The landing ring appears late. Move from the trajectory.",cf.cx,dp(558),dp(11),MUTED,false);
        metric(c,dp(20),dp(584),"READ STREAK",rallyStreak+"   •   BEST "+rallyBest);
        textCentered(c,"LIVES  "+livesString(rallyLives),cf.cx,dp(656),dp(13),rallyLives==1?RED:MUTED,true);
    }

    private void drawTwoShot(Canvas c,long now){
        CourtFrame cf=drawCourtFrame(c);
        updateCourtPlayer();
        float playerX=cf.cx+rallyPlayerX*(cf.width*.42f);
        float playerY=dp(390)+rallyPlayerY*dp(88);
        playerX=clamp(playerX,cf.left+dp(18),cf.right-dp(18));
        playerY=clamp(playerY,cf.netY+dp(28),cf.bottom-dp(20));

        if(!rallyBallActive && now>=nextPromptAt){
            if(twoShotStep==0){
                spawnCourtBall(now,1300);
            } else {
                spawnOppositeCourtBall(now,900);
            }
        }

        if(rallyBallActive){
            float[] target=drawIncomingBall(c,cf,now,true,0f);
            if(target[2]>=1f){
                trials++;
                boolean hit=(float)Math.hypot(playerX-target[0],playerY-target[1])<=dp(48);
                rallyBallActive=false;
                if(hit){
                    if(twoShotStep==0){
                        twoShotStep=1;
                        feedbackPulse(18);
                        nextPromptAt=now+110;
                    } else {
                        twoShotStep=0;
                        hits++;
                        rallyStreak++;
                        rallyBest=Math.max(rallyBest,rallyStreak);
                        nextPromptAt=now+360;
                        feedback(true);
                    }
                } else {
                    twoShotStep=0;
                    misses++;rallyLives--;rallyStreak=0;nextPromptAt=now+360;feedback(false);
                }
            }
        }

        drawCourtPlayer(c,playerX,playerY);
        if(rallyLives<=0){finishGame(clampInt(rallyBest*10+hits*3,0,100),"BEST COMBO",rallyBest+" pairs");return;}
        textCentered(c,twoShotStep==0?"FIRST BALL":"SECOND BALL",cf.cx,dp(535),dp(13),twoShotStep==0?TEXT:ORANGE,true);
        textCentered(c,"Two fast interceptions. Stay balanced between them.",cf.cx,dp(558),dp(11),MUTED,false);
        metric(c,dp(20),dp(584),"COMBO STREAK",rallyStreak+"   •   BEST "+rallyBest);
        textCentered(c,"LIVES  "+livesString(rallyLives),cf.cx,dp(656),dp(13),rallyLives==1?RED:MUTED,true);
    }

    private static class CourtFrame{
        float left,right,top,bottom,netY,cx,width;
    }

    private CourtFrame drawCourtFrame(Canvas c){
        CourtFrame cf=new CourtFrame();
        float w=getWidth();
        cf.left=dp(34);cf.right=w-dp(34);cf.top=dp(92);cf.bottom=dp(500);cf.netY=dp(280);cf.cx=w/2f;cf.width=cf.right-cf.left;
        roundRect(c,new RectF(cf.left,cf.top,cf.right,cf.bottom),PANEL,dp(8));
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setColor(MUTED);
        c.drawRect(cf.left,cf.top,cf.right,cf.bottom,p);
        c.drawLine(cf.left,cf.netY,cf.right,cf.netY,p);
        c.drawLine(cf.cx,cf.netY,cf.cx,cf.bottom,p);
        c.drawLine(cf.left,dp(395),cf.right,dp(395),p);
        p.setStyle(Paint.Style.FILL);
        return cf;
    }

    private void updateCourtPlayer(){
        float targetPlayerX=softTilt(mappedRollDelta(),.025f,.42f);
        float targetPlayerY=softTilt(mappedPitchDelta(),.025f,.42f);
        rallyPlayerX += (targetPlayerX-rallyPlayerX)*.13f;
        rallyPlayerY += (targetPlayerY-rallyPlayerY)*.13f;
    }

    private void drawCourtPlayer(Canvas c,float x,float y){
        circle(c,x,y,dp(17),LIME);
        circle(c,x,y,dp(6),BG);
    }

    private void spawnCourtBall(long now,long travelMs){
        int lane=random.nextInt(3),depth=random.nextInt(2);
        rallyTargetX=lane==0?-.78f:(lane==1?0f:.78f);
        rallyTargetY=depth==0?.18f:.78f;
        lastCourtTargetX=rallyTargetX;lastCourtTargetY=rallyTargetY;
        rallyBallStart=now;rallyTravelMs=travelMs;rallyBallActive=true;
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2,35);
    }

    private void spawnOppositeCourtBall(long now,long travelMs){
        rallyTargetX=lastCourtTargetX<-.2f?.78f:(lastCourtTargetX>.2f?-.78f:(random.nextBoolean()?-.78f:.78f));
        rallyTargetY=lastCourtTargetY<.5f?.78f:.18f;
        lastCourtTargetX=rallyTargetX;lastCourtTargetY=rallyTargetY;
        rallyBallStart=now;rallyTravelMs=travelMs;rallyBallActive=true;
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2,35);
    }

    private float[] drawIncomingBall(Canvas c,CourtFrame cf,long now,boolean showRing,float revealAt){
        float tx=cf.cx+rallyTargetX*(cf.width*.40f);
        float ty=cf.netY+dp(42)+rallyTargetY*dp(155);
        float progress=clamp((now-rallyBallStart)/(float)rallyTravelMs,0f,1f);

        if(showRing && progress>=revealAt){
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(4));p.setColor(CYAN);
            c.drawCircle(tx,ty,dp(34),p);p.setStyle(Paint.Style.FILL);circle(c,tx,ty,dp(4),CYAN);
        }

        float eased=progress*progress*(3f-2f*progress);
        float ballX=cf.cx+(tx-cf.cx)*eased;
        float ballY=cf.top+dp(22)+(ty-(cf.top+dp(22)))*eased;
        circle(c,ballX,ballY,dp(7)+dp(5)*progress,ORANGE);

        if(showRing && progress>=revealAt){
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(3));p.setColor(LIME);
            RectF arc=new RectF(tx-dp(43),ty-dp(43),tx+dp(43),ty+dp(43));
            c.drawArc(arc,-90,360f*(1f-progress),false,p);p.setStyle(Paint.Style.FILL);
        }
        return new float[]{tx,ty,progress};
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
        String primary=dailyMode&&dailyIndex<5?"NEXT DRILL":"BACK TO LAB";
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
        game=g;screen=Screen.GAME;
        gameDurationMs=(g==Game.BALANCE||g==Game.PRECISION)?30000:(g==Game.RALLY?60000:45000);

        // Every drill gets its own neutral reference. This prevents a calibration
        // made in the hand from shifting the ball when the phone is later placed flat.
        sessionCalibrating=true;
        autoCalibStart=SystemClock.elapsedRealtime();
        autoStableSince=0;
        autoRollSum=autoPitchSum=0f;
        autoCalibSamples=0;
        gameStart=autoCalibStart;
        nextPromptAt=gameStart+900;promptAt=promptDeadline=0;currentDir=-1;waitingResponse=false;
        hits=misses=trials=0;reactionTotal=0;scoreAccumulator=0;scoreFrames=0;maxObservedAccel=0;rallyStreak=rallyBest=0;rallyLives=3;chaosRound=0;chaosReverse=false;stableSince=0;neutralReady=true;lastGestureAt=0;
        balanceX=balanceY=precisionX=precisionY=0f;
        rallyPlayerX=rallyPlayerY=0f;
        rallyTargetX=rallyTargetY=0f;
        rallyBallActive=false;
        rallyBallStart=0;
        rallyTravelMs=1550;
        courtRecovering=false;
        courtRecoverStart=0;
        twoShotStep=0;
        lastCourtTargetX=lastCourtTargetY=0f;
        motionMapActive=false;
        motionMapStage=0;
        motionMapNeutralSince=0;
        feedbackPulse(18);
    }

    private void drawAutoCalibration(Canvas c,long now){
        float w=getWidth();

        // Use gyro + linear acceleration only to decide whether the phone is calm enough
        // to establish a reliable neutral reference.
        float gyroMag=(float)Math.sqrt(gx*gx+gy*gy+gz*gz);
        boolean calm=gyroMag<0.22f && linearMag<0.55f;

        if(calm){
            if(autoStableSince==0){
                autoStableSince=now;
                autoRollSum=autoPitchSum=0f;
                autoCalibSamples=0;
            }
            autoRollSum+=filteredRoll;
            autoPitchSum+=filteredPitch;
            autoCalibSamples++;
        } else {
            autoStableSince=0;
            autoRollSum=autoPitchSum=0f;
            autoCalibSamples=0;
        }

        long stableMs=autoStableSince==0?0:now-autoStableSince;
        float progress=clamp(stableMs/900f,0f,1f);

        textCentered(c,"AUTO-CENTER",w/2f,dp(180),dp(16),ORANGE,true);
        textCentered(c,calibrationInstruction(),w/2f,dp(215),dp(15),TEXT,true);

        circle(c,w/2f,dp(320),dp(74),PANEL2);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(6));
        p.setColor(calm?LIME:ORANGE);
        RectF arc=new RectF(w/2f-dp(58),dp(262),w/2f+dp(58),dp(378));
        c.drawArc(arc,-90,360f*progress,false,p);
        p.setStyle(Paint.Style.FILL);
        circle(c,w/2f,dp(320),dp(12),calm?LIME:ORANGE);

        textCentered(c,calm?"HOLD STILL":"SETTLE PHONE",w/2f,dp(425),dp(20),calm?LIME:ORANGE,true);
        textCentered(c,"The current position becomes neutral.",w/2f,dp(454),dp(13),MUTED,false);

        if(progress>=1f && autoCalibSamples>10){
            calibRoll=autoRollSum/autoCalibSamples;
            calibPitch=autoPitchSum/autoCalibSamples;
            calibrated=true;
            sessionCalibrating=false;
            balanceX=balanceY=precisionX=precisionY=0f;
            neutralReady=true;
            prefs.edit().putBoolean("calibrated",true).putFloat("calibRoll",calibRoll).putFloat("calibPitch",calibPitch).apply();

            if(needsMotionMap(game) && !motionMapReady){
                motionMapActive=true;
                motionMapStage=0;
                motionMapNeutralSince=0;
            } else {
                gameStart=now;
                nextPromptAt=now+900;
            }
            feedbackPulse(28);
            tone.startTone(ToneGenerator.TONE_PROP_ACK,70);
        }
    }

    private void drawMotionMap(Canvas c,long now){
        float w=getWidth();
        float rawX=filteredRoll-calibRoll;
        float rawY=filteredPitch-calibPitch;

        textCentered(c,"MOTION MAP",w/2f,dp(155),dp(15),ORANGE,true);
        textCentered(c,"One-time sensor direction setup",w/2f,dp(182),dp(13),MUTED,false);

        if(motionMapStage==0){
            textCentered(c,"→",w/2f,dp(310),dp(92),LIME,true);
            textCentered(c,"TILT RIGHT",w/2f,dp(390),dp(21),TEXT,true);
            textCentered(c,"Move the right edge of the phone downward.",w/2f,dp(420),dp(12),MUTED,false);
            if(Math.abs(rawX)>.12f && Math.abs(rawX)>Math.abs(rawY)*1.15f){
                axisSignX=rawX>0?1f:-1f;
                motionMapStage=1;
                motionMapNeutralSince=0;
                feedback(true);
            }
        } else if(motionMapStage==1){
            textCentered(c,"•",w/2f,dp(310),dp(92),CYAN,true);
            textCentered(c,"RETURN TO CENTER",w/2f,dp(390),dp(21),TEXT,true);
            textCentered(c,"Hold the phone in your neutral position.",w/2f,dp(420),dp(12),MUTED,false);
            if(Math.abs(rawX)<.055f && Math.abs(rawY)<.055f){
                if(motionMapNeutralSince==0) motionMapNeutralSince=now;
                if(now-motionMapNeutralSince>350){
                    motionMapStage=2;
                    motionMapNeutralSince=0;
                    feedbackPulse(18);
                }
            } else motionMapNeutralSince=0;
        } else if(motionMapStage==2){
            textCentered(c,"↑",w/2f,dp(310),dp(92),LIME,true);
            textCentered(c,"TILT FORWARD",w/2f,dp(390),dp(21),TEXT,true);
            textCentered(c,"Tilt the top edge of the phone away from you.",w/2f,dp(420),dp(12),MUTED,false);
            if(Math.abs(rawY)>.12f && Math.abs(rawY)>Math.abs(rawX)*1.15f){
                // Forward must move the marker toward the net, which is up on screen.
                axisSignY=rawY>0?-1f:1f;
                motionMapStage=3;
                motionMapNeutralSince=0;
                feedback(true);
            }
        } else {
            textCentered(c,"✓",w/2f,dp(310),dp(82),GREEN,true);
            textCentered(c,"RETURN TO CENTER",w/2f,dp(390),dp(21),TEXT,true);
            textCentered(c,"Mapping will be saved on this phone.",w/2f,dp(420),dp(12),MUTED,false);
            if(Math.abs(rawX)<.055f && Math.abs(rawY)<.055f){
                if(motionMapNeutralSince==0) motionMapNeutralSince=now;
                if(now-motionMapNeutralSince>350){
                    motionMapReady=true;
                    motionMapActive=false;
                    prefs.edit()
                            .putBoolean("motionMapReady",true)
                            .putFloat("axisSignX",axisSignX)
                            .putFloat("axisSignY",axisSignY)
                            .apply();
                    balanceX=balanceY=precisionX=precisionY=0f;
                    rallyPlayerX=rallyPlayerY=0f;
                    gameStart=now;
                    nextPromptAt=now+700;
                    feedback(true);
                }
            } else motionMapNeutralSince=0;
        }

        int step=Math.min(4,motionMapStage+1);
        textCentered(c,"STEP "+step+" / 4",w/2f,dp(490),dp(12),MUTED,true);
    }

    private boolean needsMotionMap(Game g){
        return g!=Game.SPLIT;
    }

    private float mappedRollDelta(){
        return (filteredRoll-calibRoll)*axisSignX;
    }

    private float mappedPitchDelta(){
        return (filteredPitch-calibPitch)*axisSignY;
    }

    private String calibrationInstruction(){
        if(game==Game.SPLIT)return"Hold the phone where you will move.";
        if(game==Game.REFLEX||game==Game.CHAOS||game==Game.RALLY||game==Game.RECOVER||game==Game.ANTICIPATION||game==Game.TWO_SHOT)return"Hold your natural ready position.";
        return"Hold the phone in your starting position.";
    }

    private void finishTimedGame(){
        int score;String metric,value;
        switch(game){
            case BALANCE:score=Math.round((scoreAccumulator/Math.max(1,scoreFrames))*100);metric="STABILITY";value=score+"/100";break;
            case PRECISION:score=Math.round((scoreAccumulator/Math.max(1,scoreFrames))*100);metric="PRECISION";value=score+"/100";break;
            case REFLEX:score=reactionScore();metric="AVG REACTION";value=hits==0?"—":Math.round(reactionTotal/hits)+" ms";break;
            case SPLIT:score=reactionScore();metric="AVG START";value=hits==0?"—":Math.round(reactionTotal/hits)+" ms";break;
            case CHAOS:score=accuracyReactionScore();metric="DECISION ACCURACY";value=trials==0?"—":Math.round(100f*hits/trials)+"%";break;
            case RECOVER:score=clampInt(rallyBest*8+hits*3,0,100);metric="BEST RECOVERY";value=rallyBest+" cycles";break;
            case ANTICIPATION:score=clampInt(rallyBest*7+hits*2,0,100);metric="BEST READ";value=rallyBest+" balls";break;
            case TWO_SHOT:score=clampInt(rallyBest*10+hits*3,0,100);metric="BEST COMBO";value=rallyBest+" pairs";break;
            default:score=clampInt(rallyBest*6+hits*2,0,100);metric="BEST RALLY";value=rallyBest+" balls";
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
        float dr=mappedRollDelta(),dpt=mappedPitchDelta();
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

    private void calibrate(){calibRoll=filteredRoll;calibPitch=filteredPitch;balanceX=balanceY=precisionX=precisionY=0f;calibrated=true;prefs.edit().putBoolean("calibrated",true).putFloat("calibRoll",calibRoll).putFloat("calibPitch",calibPitch).apply();feedback(true);}

    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getAction()!=MotionEvent.ACTION_UP)return true;float x=e.getX(),y=e.getY();
        if(screen==Screen.HOME){
            if(dailyRect.contains(x,y)){dailyMode=true;dailyIndex=0;startGame(Game.BALANCE);return true;}
            if(calibrateRect.contains(x,y)){
                motionMapReady=false;
                prefs.edit().remove("motionMapReady").remove("axisSignX").remove("axisSignY").apply();
                feedbackPulse(24);
                return true;
            }
            if(courtLabRect.contains(x,y)){screen=Screen.COURT_LAB;return true;}
            Game[] main={Game.BALANCE,Game.PRECISION,Game.REFLEX,Game.SPLIT,Game.CHAOS,Game.RALLY};
            for(Game g:main){RectF r=gameRects.get(g);if(r!=null&&r.contains(x,y)){dailyMode=false;startGame(g);return true;}}
        } else if(screen==Screen.COURT_LAB){
            if(y<dp(80)){screen=Screen.HOME;return true;}
            if(courtProgressRect.contains(x,y)){screen=Screen.PROGRESS;return true;}
            Game[] visual={Game.RECOVER,Game.ANTICIPATION,Game.TWO_SHOT};
            for(Game g:visual){RectF r=courtGameRects.get(g);if(r!=null&&r.contains(x,y)){dailyMode=false;startGame(g);return true;}}
        } else if(screen==Screen.GAME){
            if(y<dp(72)){screen=Screen.HOME;dailyMode=false;return true;}
            if(!calibrated&&calibrateRect.contains(x,y)){startGame(game);return true;}
        } else if(screen==Screen.RESULT){
            if(resultPrimaryRect.contains(x,y)){if(dailyMode&&dailyIndex<5){dailyIndex++;Game[] dailyGames={Game.BALANCE,Game.PRECISION,Game.REFLEX,Game.SPLIT,Game.CHAOS,Game.RALLY};startGame(dailyGames[dailyIndex]);}else{dailyMode=false;screen=Screen.HOME;}return true;}
            if(resultSecondaryRect.contains(x,y)){startGame(game);return true;}
        } else if(screen==Screen.PROGRESS&&y<dp(80)){screen=Screen.HOME;return true;}
        return true;
    }

    private void feedback(boolean ok){feedbackPulse(ok?24:65);tone.startTone(ok?ToneGenerator.TONE_PROP_ACK:ToneGenerator.TONE_PROP_NACK,ok?45:80);}
    private void feedbackPulse(long ms){if(vibrator!=null&&vibrator.hasVibrator())vibrator.vibrate(VibrationEffect.createOneShot(ms,VibrationEffect.DEFAULT_AMPLITUDE));}
    private int getLevel(){return Math.min(50,1+prefs.getInt("xp",0)/400);}
    private float getLevelProgress(){return(prefs.getInt("xp",0)%400)/400f;}
    private String getLeague(int l){if(l<=10)return"FOUNDATION";if(l<=20)return"CONTROL";if(l<=30)return"REACTION";if(l<=40)return"MOVEMENT";return"TENNIS IQ";}
    private String gameTitle(Game g){switch(g){case BALANCE:return"BALANCE";case PRECISION:return"PRECISION";case REFLEX:return"REFLEX";case SPLIT:return"SPLIT STEP";case CHAOS:return"CHAOS";case RALLY:return"RALLY REFLEX";case RECOVER:return"RECOVER";case ANTICIPATION:return"ANTICIPATION";default:return"TWO SHOT";}}
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
    private float softTilt(float value,float deadZone,float maxTilt){
        float sign=value<0?-1f:1f,mag=Math.abs(value);
        if(mag<=deadZone)return 0f;
        float normalized=clamp((mag-deadZone)/(maxTilt-deadZone),0f,1f);
        // Power curve gives much finer control around center, while preserving full range.
        float curved=(float)Math.pow(normalized,1.55);
        return sign*curved;
    }
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
    private float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private int clampInt(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
}
