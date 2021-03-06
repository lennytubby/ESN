package de.cogmod.spacecombat;


import java.io.*;
import java.util.List;
import java.util.Arrays;
import java.util.Random;

import de.cogmod.rgnns.EchoStateNetwork;
import de.cogmod.rgnns.RecurrentNeuralNetwork;
import de.cogmod.rgnns.math.Vector3d;
import de.cogmod.spacecombat.AIMComputer;
import de.cogmod.spacecombat.simulation.EnemySpaceShip;
import de.cogmod.spacecombat.simulation.Missile;
import de.cogmod.spacecombat.simulation.SpaceSimulation;
import de.cogmod.spacecombat.simulation.SpaceSimulationObserver;
import de.jannlab.io.Serializer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;

/**
 * @author Sebastian Otte
 */
public class AIMComputer implements SpaceSimulationObserver {

    public final static int PREDICTION_LENGTH = 100;
    
    private EnemySpaceShip enemy        = null;
    private boolean        targetlocked = false;
    private boolean		   aimissile    = true; // Enables forbidden AI missiles. 
    
    private Vector3d[] enemytrajectoryprediction;
    private double[] missiledistancelut;
    
    private Missile missile;
    private int missiletime;
    private Vector3d target;
    
    private EchoStateNetwork enemyesn;
    private EchoStateNetwork enemyesncopy;
    private RecurrentNeuralNetwork missilernn;
    
    public Vector3d[] getEnemyTrajectoryPrediction() {
        return this.enemytrajectoryprediction;
    }
    
    public boolean getTargetLocked() {
        return this.targetlocked;
    }
    
    public EnemySpaceShip getTarget() {
        return this.enemy;
    }
    
    public void releaseTarget() {
        synchronized (this) {
            this.enemy = null;
            this.targetlocked = false;
        }
    }
    
    public void lockTarget(final EnemySpaceShip enemy) {
        synchronized (this) {
            this.enemy        = enemy;
            this.targetlocked = true;
            this.enemyesn.reset();
        }
    }
    
    private Vector3d[] generateESNFutureProjection(final int timesteps) {
    	//
    	// TODO: Implement me.
    	//
        double[][] pred = new double [timesteps][3];
        for (int t=0; t<timesteps; t++){
            pred[t] = this.enemyesncopy.forwardPassOscillator();
/*
            pred[t][0] = pred[t][0] * 1e-1;
            pred[t][1] = pred[t][1] * 1e-1;
            pred[t][2] = pred[t][2] * 1e-1;
*/
        }
        //
        Vector3d last           = this.enemy.getRelativePosition();
        final Vector3d dir      = new Vector3d();
        final Vector3d[] result = new Vector3d[timesteps];
        //
        for (int t = 0; t < timesteps; t++) {
            dir.x = pred[t][0] ;
            dir.y = pred[t][1];
            dir.z = pred[t][2];

            //final Vector3d current = Vector3d.add(last, dir);
            result[t] = Vector3d.add(dir, enemy.getOrigin());
            //last = current;
        }
        return result;
    }
    
    // maybe only required for dummy trajectory.
    private Random rnd = new Random(1234);

    private Vector3d[] generateDummyFutureProjection(final int timesteps) {
        //
        Vector3d last           = this.enemy.getRelativePosition();
        final Vector3d dir      = new Vector3d();
        final Vector3d[] result = new Vector3d[timesteps]; 
        //
        for (int t = 0; t < timesteps; t++) {
            dir.x += rnd.nextGaussian() * 0.1;
            dir.y += rnd.nextGaussian() * 0.1;
            dir.z += rnd.nextGaussian() * 0.1;
            //
            Vector3d.normalize(dir, dir);
            //
            dir.x *= 1.0;
            dir.y *= 1.0;
            dir.z *= 1.0;
            
            final Vector3d current = Vector3d.add(last, dir);
            result[t] = Vector3d.add(current, enemy.getOrigin());
            last = current;
        }
        return result;
    }
    
    @Override
    public void simulationStep(final SpaceSimulation sim) {
        //
        boolean record = false;
        //
        synchronized (this) {
            //
            if (!this.targetlocked) return;
            //
            // update trajectory prediction RNN (teacher forcing)
            //
            final Vector3d enemyrelativeposition = sim.getEnemy().getRelativePosition();
            //
            final double[] update = {
                enemyrelativeposition.x,
                enemyrelativeposition.y,
                enemyrelativeposition.z
            };
            //
            // TODO: Update trained ESN with current observation (teacher forcing) ...
            //
            if (record) {
                // Only Record Trajectory
                try {
                    writeSequence( "data/Sequence.txt",update);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            else {
                double[] output  = this.enemyesn.forwardPassOscillator();
                this.enemyesn.teacherForcing(update);

                this.enemyesncopy = new EchoStateNetwork(3, this.enemyesn.reservoirsize, 3);
                double[] weights = new double[this.enemyesn.getWeightsNum()];
                this.enemyesn.readWeights(weights);
                this.enemyesncopy.writeWeights(weights);

                double[][][] enemyAct = this.enemyesn.getAct();
                double[][][] enemyCopyAct = this.enemyesncopy.getAct();
                for (int i=0; i<enemyAct.length;i++){
                    for (int j=0; j<enemyAct[i].length;j++){
                        for (int k=0; k<enemyAct[i][j].length;k++){
                            enemyCopyAct[i][j][k] = enemyAct[i][j][k];
                        }
                    }
                }

                //this.enemyesncopy.forwardPassOscillator();
                //this.enemyesncopy.teacherForcing(update);
            }
            
            //
            // use copy of the RNN to generate future projection (replace dummy method).
            //
            //this.enemytrajectoryprediction = this.generateDummyFutureProjection(PREDICTION_LENGTH);
            this.enemytrajectoryprediction = generateESNFutureProjection(PREDICTION_LENGTH);
            //
            // grab the most recently launched missile that is alive.
            //
            final Missile currentMissile = lastActiveMissile(sim);
            //
            // control missile with the magic control RNN.
            //
            if (currentMissile != null && aimissile) {
                this.updateMissileController(currentMissile);
            }
        }
    }
    
    /**
     * Loads pretrained ESN weights from file. Note that weights can be easily
     * stored in a file using 
     * <br> 
     * Serializer.write(w, f); 
     * <br>
     * where w is a flattened weights array (cf. readWeights/writeWeights) and
     * f is the filename.
     */
    private void loadESN() {
        try {
            //
            // load esn.
            //
            final int reservoirsize = 60; // use reasonable value here.
            this.enemyesn     = new EchoStateNetwork(3, reservoirsize, 3);
            this.enemyesncopy = new EchoStateNetwork(3, reservoirsize, 3);
            //
            // TODO: load pretrained ESN.
            //
             // !!! uncomment block to load ESN weight from file.
            
            final String esnweightsfile = (
                "data/esn-3-" + 
                reservoirsize + "-3.weights"
            );
            final double[] weights = Serializer.read(esnweightsfile);
            //
            this.enemyesn.writeWeights(weights);

            this.enemyesncopy.writeWeights(weights);

            //
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    // ----------------------------------------------------------------
    // The code below is not directly relevant for pursuing the 
    // exercise.
    // ----------------------------------------------------------------
    
    private Vector3d prevMissilePos;
    
    private void resetMissileController() {
        this.missilernn.reset();
        this.prevMissilePos = this.missile.getPosition().copy();
        this.missiletime = 0;
    }
    
    private Vector3d linearTargetEstimation() {
        final double startDist = this.missiledistancelut[this.missiletime];
        final Vector3d missilePos = this.missile.getPosition();
        Vector3d target = null;
        
        double minDistDeviation = Double.POSITIVE_INFINITY;
        
        for (int t = 0; t < PREDICTION_LENGTH; t++) {
            final Vector3d predPos = this.enemytrajectoryprediction[t];
            
            final double distMissileEnemy = Vector3d.sub(
                predPos, 
                missilePos
            ).length();
            
            if ((this.missiletime + t) < this.missiledistancelut.length) {
                final double distEstimate = (this.missiledistancelut[this.missiletime + t] - startDist);
                final double distDeviation = Math.abs(distEstimate - distMissileEnemy);
                
                if (distDeviation < minDistDeviation) {
                    minDistDeviation = distDeviation;
                    target = predPos;
                }
            }
        }
        
        return target;
    }
    
    private void updateMissileController(final Missile missile) {
        if (this.missile != missile) {
            this.missile = missile;
            this.resetMissileController();
        }
        //
        final Vector3d missilePos = missile.getPosition().copy();
        final Vector3d missileVel = Vector3d.sub(missilePos, this.prevMissilePos);
        //
        this.target = this.linearTargetEstimation();
                
        final Vector3d vecToTarget = Vector3d.sub(this.target, missilePos);
        final Vector3d dirToTarget = Vector3d.normalize(vecToTarget);

        final double scaleVel = 0.2;
        final double scaleTarget = 0.1;

        final double[] input = new double[] {
            scaleVel * missileVel.x,
            scaleVel * missileVel.y,
            scaleVel * missileVel.z,
            scaleTarget * dirToTarget.x,
            scaleTarget * dirToTarget.y,
            scaleTarget * dirToTarget.z
        };
        //
        final double[] output = this.missilernn.forwardPass(input);
        //
        missile.adjust(
            2.0 * output[0],
            2.0 * output[1]
        );
        //
        this.prevMissilePos = missilePos; 
        this.missiletime++;
    }
    
    
    /**
     * Returns the most recently launched missile within the simulation, but only
     * if it is still "alive". Otherwise the method returns null. 
     */
    private Missile lastActiveMissile(final SpaceSimulation sim) {
        final List<Missile> missiles = sim.getMissiles();
        if (missiles.size() > 0) {
            final Missile lastMissile = missiles.get(missiles.size() - 1);
            if (lastMissile.isLaunched() && !lastMissile.isDestroyed()) {
                return lastMissile;
            }
        }
        return null;
    }
    
    /**
     * Loads the pretrained missile control RNN.
     */
    private void loadRNN() {
        try {
            //
            // load missile controller rnn.
            //
            this.missilernn = new RecurrentNeuralNetwork(6, 6, 2);
            //
            final String esnweightsfile = (
                "data/missileRNN.weights"
            );
            final double[] weights = Serializer.read(esnweightsfile);
            //
            this.missilernn.writeWeights(weights);
            //
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * Lookup table for target estimation.
     */
    private void initMissileDistanceLUT() {
        this.missiledistancelut = new double[(int)(Missile.MAX_LIFETIME)];
        
        final Missile m = new Missile();
        m.launch();
        
        m.update();
        
        double fade = 0.2;
        
        for (int i = 0; i < this.missiledistancelut.length; i++) {
            final double dist = m.getPosition().length();
            this.missiledistancelut[i] = dist * (1.0 - fade);
            m.update();
            fade *= 0.95;
        }
        
    }
    public static void writeSequence(final String filepath,final double[] values) throws IOException {
        //File fout = new File(filepath);
        //FileOutputStream fos = new FileOutputStream(filepath);
        //BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos));
        BufferedWriter bw = new BufferedWriter(new FileWriter(filepath,true));

        String tempStr = "";
        for (int i = 0; i < values.length -1; i++) {
            tempStr += String.valueOf(values[i]) + " ";
        }
        tempStr += String.valueOf(values[values.length -1]);
        bw.append(tempStr);
        bw.newLine();

        bw.close();
    }
    public AIMComputer() {
        this.loadESN();
        this.loadRNN();
        this.initMissileDistanceLUT();
    }   
}