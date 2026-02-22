import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  StyleSheet,
  Text,
  View,
  TouchableOpacity,
  ScrollView,
  Dimensions,
  Platform,
} from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { Accelerometer } from 'expo-sensors';

const { width: SCREEN_WIDTH } = Dimensions.get('window');
const G = 9.81;
const SWING_THRESHOLD = 2.5; // in G's — minimum to count as a swing
const SWING_COOLDOWN_MS = 600;
const GRAPH_POINTS = 80;
const GRAPH_HEIGHT = 140;
const GRAPH_MAX_G = 8;

function classifySwing(maxG) {
  if (maxG >= 6) return { label: 'Potente!', color: '#e53935' };
  if (maxG >= 4) return { label: 'Forte', color: '#ff9800' };
  if (maxG >= 2.5) return { label: 'Moderado', color: '#4caf50' };
  return { label: 'Leve', color: '#90a4ae' };
}

function formatTime(date) {
  return date.toLocaleTimeString('pt-BR', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

function MiniGraph({ data, maxVal, color, height }) {
  if (data.length < 2) return null;
  const step = SCREEN_WIDTH / (GRAPH_POINTS - 1);
  const points = data
    .map((val, i) => {
      const x = i * step;
      const y = height - (Math.min(val, maxVal) / maxVal) * height;
      return `${x},${y}`;
    })
    .join(' ');

  return (
    <View style={[styles.graphContainer, { height }]}>
      {/* Grid lines */}
      {[0.25, 0.5, 0.75].map((frac) => (
        <View
          key={frac}
          style={[
            styles.gridLine,
            { top: height * frac },
          ]}
        />
      ))}
      <Text style={[styles.graphLabel, { top: 0 }]}>{maxVal}G</Text>
      <Text style={[styles.graphLabel, { top: height / 2 - 8 }]}>{maxVal / 2}G</Text>
      <Text style={[styles.graphLabel, { bottom: 0 }]}>0</Text>

      {/* SVG-like bar graph using views */}
      <View style={styles.barsContainer}>
        {data.map((val, i) => {
          const barH = (Math.min(val, maxVal) / maxVal) * height;
          return (
            <View
              key={i}
              style={{
                width: Math.max((SCREEN_WIDTH - 60) / GRAPH_POINTS - 1, 1),
                height: barH,
                backgroundColor: val >= SWING_THRESHOLD ? color : 'rgba(255,255,255,0.2)',
                marginRight: 1,
                alignSelf: 'flex-end',
                borderRadius: 1,
              }}
            />
          );
        })}
      </View>
    </View>
  );
}

export default function App() {
  const [isTracking, setIsTracking] = useState(false);
  const [accelData, setAccelData] = useState({ x: 0, y: 0, z: 0 });
  const [totalG, setTotalG] = useState(0);
  const [maxG, setMaxG] = useState(0);
  const [swings, setSwings] = useState([]);
  const [graphData, setGraphData] = useState(new Array(GRAPH_POINTS).fill(0));
  const [swingCount, setSwingCount] = useState(0);

  const subscriptionRef = useRef(null);
  const lastSwingTimeRef = useRef(0);
  const currentMaxRef = useRef(0);
  const inSwingRef = useRef(false);
  const maxGRef = useRef(0);

  const startTracking = useCallback(async () => {
    setMaxG(0);
    maxGRef.current = 0;
    setGraphData(new Array(GRAPH_POINTS).fill(0));

    Accelerometer.setUpdateInterval(16); // ~60fps

    subscriptionRef.current = Accelerometer.addListener((data) => {
      const { x, y, z } = data;
      const magnitude = Math.sqrt(x * x + y * y + z * z);

      setAccelData({ x, y, z });
      setTotalG(magnitude);

      if (magnitude > maxGRef.current) {
        maxGRef.current = magnitude;
        setMaxG(magnitude);
      }

      setGraphData((prev) => {
        const next = [...prev.slice(1), magnitude];
        return next;
      });

      // Swing detection
      const now = Date.now();
      if (magnitude >= SWING_THRESHOLD && !inSwingRef.current) {
        if (now - lastSwingTimeRef.current > SWING_COOLDOWN_MS) {
          inSwingRef.current = true;
          currentMaxRef.current = magnitude;
        }
      } else if (inSwingRef.current) {
        if (magnitude > currentMaxRef.current) {
          currentMaxRef.current = magnitude;
        }
        if (magnitude < SWING_THRESHOLD * 0.7) {
          // Swing ended
          const swingMaxG = currentMaxRef.current;
          const classification = classifySwing(swingMaxG);
          setSwings((prev) => [
            {
              id: now,
              maxG: swingMaxG,
              ms: (swingMaxG * G).toFixed(1),
              time: new Date(),
              ...classification,
            },
            ...prev.slice(0, 49),
          ]);
          setSwingCount((c) => c + 1);
          lastSwingTimeRef.current = now;
          inSwingRef.current = false;
          currentMaxRef.current = 0;
        }
      }
    });

    setIsTracking(true);
  }, []);

  const stopTracking = useCallback(() => {
    if (subscriptionRef.current) {
      subscriptionRef.current.remove();
      subscriptionRef.current = null;
    }
    setIsTracking(false);
    inSwingRef.current = false;
    currentMaxRef.current = 0;
  }, []);

  const resetData = useCallback(() => {
    setMaxG(0);
    maxGRef.current = 0;
    setSwings([]);
    setSwingCount(0);
    setGraphData(new Array(GRAPH_POINTS).fill(0));
  }, []);

  useEffect(() => {
    return () => {
      if (subscriptionRef.current) {
        subscriptionRef.current.remove();
      }
    };
  }, []);

  const accelColor =
    totalG >= 6 ? '#e53935' : totalG >= 4 ? '#ff9800' : totalG >= 2.5 ? '#66bb6a' : '#b0bec5';

  return (
    <View style={styles.container}>
      <StatusBar style="light" />

      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.headerIcon}>🎾</Text>
        <Text style={styles.title}>Tennis Racket</Text>
        <Text style={styles.subtitle}>Acelerômetro</Text>
      </View>

      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}
      >
        {/* Main G Display */}
        <View style={styles.mainDisplay}>
          <Text style={styles.gLabel}>ACELERAÇÃO ATUAL</Text>
          <Text style={[styles.gValue, { color: accelColor }]}>
            {totalG.toFixed(2)}
          </Text>
          <Text style={styles.gUnit}>G</Text>
          <Text style={styles.msValue}>
            {(totalG * G).toFixed(1)} m/s²
          </Text>
        </View>

        {/* Axis data */}
        <View style={styles.axisRow}>
          <View style={styles.axisBox}>
            <Text style={styles.axisLabel}>X</Text>
            <Text style={[styles.axisValue, { color: '#ef5350' }]}>
              {accelData.x.toFixed(2)}
            </Text>
          </View>
          <View style={styles.axisBox}>
            <Text style={styles.axisLabel}>Y</Text>
            <Text style={[styles.axisValue, { color: '#66bb6a' }]}>
              {accelData.y.toFixed(2)}
            </Text>
          </View>
          <View style={styles.axisBox}>
            <Text style={styles.axisLabel}>Z</Text>
            <Text style={[styles.axisValue, { color: '#42a5f5' }]}>
              {accelData.z.toFixed(2)}
            </Text>
          </View>
        </View>

        {/* Graph */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>GRÁFICO EM TEMPO REAL</Text>
          <MiniGraph
            data={graphData}
            maxVal={GRAPH_MAX_G}
            color="#66bb6a"
            height={GRAPH_HEIGHT}
          />
        </View>

        {/* Stats */}
        <View style={styles.statsRow}>
          <View style={styles.statBox}>
            <Text style={styles.statLabel}>PICO MÁXIMO</Text>
            <Text style={[styles.statValue, { color: '#ffca28' }]}>
              {maxG.toFixed(2)} G
            </Text>
          </View>
          <View style={styles.statBox}>
            <Text style={styles.statLabel}>TACADAS</Text>
            <Text style={[styles.statValue, { color: '#66bb6a' }]}>
              {swingCount}
            </Text>
          </View>
          <View style={styles.statBox}>
            <Text style={styles.statLabel}>MÉDIA</Text>
            <Text style={[styles.statValue, { color: '#42a5f5' }]}>
              {swings.length > 0
                ? (swings.reduce((s, sw) => s + sw.maxG, 0) / swings.length).toFixed(2)
                : '—'}{' '}
              G
            </Text>
          </View>
        </View>

        {/* Controls */}
        <View style={styles.controlsRow}>
          <TouchableOpacity
            style={[
              styles.mainButton,
              isTracking ? styles.stopButton : styles.startButton,
            ]}
            onPress={isTracking ? stopTracking : startTracking}
            activeOpacity={0.8}
          >
            <Text style={styles.mainButtonText}>
              {isTracking ? '⏹  PARAR' : '▶  INICIAR'}
            </Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.resetButton}
            onPress={resetData}
            activeOpacity={0.7}
          >
            <Text style={styles.resetButtonText}>RESETAR</Text>
          </TouchableOpacity>
        </View>

        {/* Swing History */}
        {swings.length > 0 && (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>
              HISTÓRICO DE TACADAS ({swings.length})
            </Text>
            {swings.map((swing, index) => (
              <View key={swing.id} style={styles.swingItem}>
                <View style={styles.swingLeft}>
                  <View
                    style={[
                      styles.swingDot,
                      { backgroundColor: swing.color },
                    ]}
                  />
                  <View>
                    <Text style={styles.swingIndex}>
                      #{swingCount - index}
                    </Text>
                    <Text style={styles.swingTime}>
                      {formatTime(swing.time)}
                    </Text>
                  </View>
                </View>
                <View style={styles.swingRight}>
                  <Text
                    style={[styles.swingLabel, { color: swing.color }]}
                  >
                    {swing.label}
                  </Text>
                  <Text style={styles.swingG}>
                    {swing.maxG.toFixed(2)} G
                  </Text>
                  <Text style={styles.swingMs}>{swing.ms} m/s²</Text>
                </View>
              </View>
            ))}
          </View>
        )}

        {/* Tips */}
        <View style={styles.tipsBox}>
          <Text style={styles.tipsTitle}>💡 DICAS</Text>
          <Text style={styles.tipText}>
            • Segure o celular firme na mão da raquete
          </Text>
          <Text style={styles.tipText}>
            • Simule tacadas para medir a aceleração
          </Text>
          <Text style={styles.tipText}>
            • Tacadas acima de 4G são consideradas fortes
          </Text>
          <Text style={styles.tipText}>
            • O app detecta automaticamente cada tacada
          </Text>
        </View>

        <View style={{ height: 40 }} />
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#1a2332',
  },
  header: {
    paddingTop: Platform.OS === 'ios' ? 60 : 48,
    paddingBottom: 16,
    alignItems: 'center',
    backgroundColor: '#243447',
    borderBottomWidth: 2,
    borderBottomColor: '#66bb6a',
  },
  headerIcon: {
    fontSize: 32,
    marginBottom: 4,
  },
  title: {
    fontSize: 22,
    fontWeight: '800',
    color: '#ffffff',
    letterSpacing: 1,
  },
  subtitle: {
    fontSize: 13,
    color: '#66bb6a',
    fontWeight: '600',
    letterSpacing: 2,
    textTransform: 'uppercase',
  },
  scrollView: {
    flex: 1,
  },
  scrollContent: {
    paddingHorizontal: 16,
    paddingTop: 16,
  },
  mainDisplay: {
    alignItems: 'center',
    paddingVertical: 20,
    backgroundColor: '#243447',
    borderRadius: 16,
    marginBottom: 12,
  },
  gLabel: {
    fontSize: 11,
    color: '#78909c',
    fontWeight: '700',
    letterSpacing: 2,
  },
  gValue: {
    fontSize: 72,
    fontWeight: '900',
    lineHeight: 80,
  },
  gUnit: {
    fontSize: 24,
    color: '#78909c',
    fontWeight: '700',
    marginTop: -4,
  },
  msValue: {
    fontSize: 16,
    color: '#546e7a',
    fontWeight: '600',
    marginTop: 4,
  },
  axisRow: {
    flexDirection: 'row',
    gap: 8,
    marginBottom: 12,
  },
  axisBox: {
    flex: 1,
    backgroundColor: '#243447',
    borderRadius: 12,
    padding: 12,
    alignItems: 'center',
  },
  axisLabel: {
    fontSize: 12,
    color: '#78909c',
    fontWeight: '700',
  },
  axisValue: {
    fontSize: 20,
    fontWeight: '800',
    marginTop: 4,
  },
  section: {
    backgroundColor: '#243447',
    borderRadius: 16,
    padding: 16,
    marginBottom: 12,
  },
  sectionTitle: {
    fontSize: 11,
    color: '#78909c',
    fontWeight: '700',
    letterSpacing: 2,
    marginBottom: 12,
  },
  graphContainer: {
    position: 'relative',
    overflow: 'hidden',
    borderRadius: 8,
    backgroundColor: '#1a2332',
    padding: 4,
  },
  gridLine: {
    position: 'absolute',
    left: 30,
    right: 0,
    height: 1,
    backgroundColor: 'rgba(255,255,255,0.05)',
  },
  graphLabel: {
    position: 'absolute',
    left: 2,
    fontSize: 9,
    color: '#546e7a',
    fontWeight: '600',
  },
  barsContainer: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    height: '100%',
    marginLeft: 30,
  },
  statsRow: {
    flexDirection: 'row',
    gap: 8,
    marginBottom: 12,
  },
  statBox: {
    flex: 1,
    backgroundColor: '#243447',
    borderRadius: 12,
    padding: 14,
    alignItems: 'center',
  },
  statLabel: {
    fontSize: 9,
    color: '#78909c',
    fontWeight: '700',
    letterSpacing: 1,
    marginBottom: 6,
  },
  statValue: {
    fontSize: 18,
    fontWeight: '800',
  },
  controlsRow: {
    flexDirection: 'row',
    gap: 10,
    marginBottom: 16,
  },
  mainButton: {
    flex: 3,
    paddingVertical: 16,
    borderRadius: 14,
    alignItems: 'center',
  },
  startButton: {
    backgroundColor: '#66bb6a',
  },
  stopButton: {
    backgroundColor: '#e53935',
  },
  mainButtonText: {
    fontSize: 18,
    fontWeight: '800',
    color: '#fff',
    letterSpacing: 1,
  },
  resetButton: {
    flex: 1,
    paddingVertical: 16,
    borderRadius: 14,
    alignItems: 'center',
    backgroundColor: '#37474f',
  },
  resetButtonText: {
    fontSize: 12,
    fontWeight: '700',
    color: '#90a4ae',
    letterSpacing: 1,
  },
  swingItem: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255,255,255,0.05)',
  },
  swingLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  swingDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
  },
  swingIndex: {
    fontSize: 14,
    fontWeight: '700',
    color: '#cfd8dc',
  },
  swingTime: {
    fontSize: 11,
    color: '#546e7a',
  },
  swingRight: {
    alignItems: 'flex-end',
  },
  swingLabel: {
    fontSize: 12,
    fontWeight: '800',
    letterSpacing: 1,
  },
  swingG: {
    fontSize: 16,
    fontWeight: '800',
    color: '#eceff1',
  },
  swingMs: {
    fontSize: 11,
    color: '#546e7a',
  },
  tipsBox: {
    backgroundColor: '#1b3a2a',
    borderRadius: 14,
    padding: 16,
    borderWidth: 1,
    borderColor: '#2e7d32',
  },
  tipsTitle: {
    fontSize: 13,
    fontWeight: '800',
    color: '#66bb6a',
    marginBottom: 8,
    letterSpacing: 1,
  },
  tipText: {
    fontSize: 13,
    color: '#a5d6a7',
    lineHeight: 22,
  },
});
