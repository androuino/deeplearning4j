package org.deeplearning4j.nn.graph;

import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.datasets.iterator.DataSetIterator;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.Model;
import org.deeplearning4j.nn.conf.BackpropType;
import org.deeplearning4j.nn.conf.ComputationGraphConfiguration;
import org.deeplearning4j.nn.conf.InputPreProcessor;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.graph.nodes.GraphNode;
import org.deeplearning4j.nn.graph.vertex.GraphVertex;
import org.deeplearning4j.nn.graph.vertex.VertexIndices;
import org.deeplearning4j.nn.layers.BaseOutputLayer;
import org.deeplearning4j.nn.layers.BasePretrainNetwork;
import org.deeplearning4j.nn.layers.factory.LayerFactories;
import org.deeplearning4j.nn.layers.recurrent.BaseRecurrentLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.updater.UpdaterCreator;
import org.deeplearning4j.nn.updater.graph.ComputationGraphUpdater;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.Solver;
import org.deeplearning4j.optimize.api.ConvexOptimizer;
import org.deeplearning4j.optimize.api.IterationListener;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.MultiDataSet;
import org.nd4j.linalg.dataset.api.iterator.MultiDataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;

/**ComputationGraph network (neural network with arbitrary connection structure)
 */
public class ComputationGraph implements Serializable, Model {

    private static final Logger log = LoggerFactory.getLogger(ComputationGraph.class);

    protected ComputationGraphConfiguration configuration;
    protected transient Solver solver;	//Used to call optimizers during backprop
    protected Gradient gradient;
    protected double score;

    protected GraphVertex[] vertices;
    protected Map<String,GraphVertex> verticesMap;
    protected int[] topologicalOrder;
    protected int numLayers;    //Number of layers (not including GraphNode objects, etc)
    protected Layer[] layers;

    private int numInputArrays;
    private int numOutputArrays;
    private int layerCount;

    private INDArray[] inputs;
    private INDArray[] labels;

    private NeuralNetConfiguration defaultConfiguration;
    private Collection<IterationListener> listeners = new ArrayList<>();


    public ComputationGraph(ComputationGraphConfiguration configuration){
        this.configuration = configuration;
        this.numInputArrays = configuration.getNetworkInputs().size();
        this.numOutputArrays = configuration.getNetworkOutputs().size();
        this.layerCount = configuration.getLayers().size();
        this.inputs = new INDArray[numInputArrays];
        this.labels = new INDArray[numOutputArrays];
        this.defaultConfiguration = configuration.getLayers().get(configuration.getLayers().keySet().iterator().next());    //TODO
    }

    public ComputationGraphConfiguration getConfiguration(){
        return configuration;
    }

    public int getNumLayers(){
        return numLayers;
    }

    /** Get the layer by the number of that layer, in range 0 to getNumLayers()-1
     * NOTE: This is different from the interval vertex index for the layer
     */
    public Layer getLayer(int idx){
        return layers[idx];
    }

    public Layer getLayer(String name){
        return verticesMap.get(name).getLayer();    //TODO checks
    }

    public GraphVertex[] getVertices(){
        return vertices;
    }

    /** The number of inputs to this network */
    public int getNumInputArrays(){
        return numInputArrays;
    }

    /** The number of output (arrays) for this network */
    public int getNumOutputArrays(){
        return numOutputArrays;
    }

    public void setInput(int inputNum, INDArray input){
        inputs[inputNum] = input;
    }

    public void setInputs(INDArray[] inputs){
        if(inputs != null && inputs.length != this.numInputArrays){
            throw new IllegalArgumentException("Invalid input array: network has " + numInputArrays + " inputs, but array is of length " + inputs.length);
        }
        this.inputs = inputs;
    }

    public void setLabel(int labelNum, INDArray label){
        labels[labelNum] = label;
    }

    public void setLabels(INDArray[] labels){
        if(labels != null && labels.length != this.numOutputArrays){
            throw new IllegalArgumentException("Invalid output array: network has " + numOutputArrays + " outputs, but array is of length " + labels.length);
        }
        this.labels = labels;
    }

    /** Initialize the ComputationGraph network */
    public void init(){
        //Initialization: create the GraphVertex objects, based on configuration structure

        Map<String,Layer> layerMap = new HashMap<>();
        for( Map.Entry<String,NeuralNetConfiguration> entry : configuration.getLayers().entrySet() ){
            String layerName = entry.getKey();
            NeuralNetConfiguration layerConf = entry.getValue();

            Layer layer = LayerFactories.getFactory(layerConf).create(layerConf, null, -1); //TODO: indices
            layerMap.put(layerName, layer);
        }

        Map<String,GraphNode> nodeMap = configuration.getGraphNodes();

        //Names of all of the (data) inputs to the ComputationGraph
        List<String> networkInputNames = configuration.getNetworkInputs();

        //Inputs for each layer and GraphNode:
        Map<String,List<String>> layerInputs = configuration.getLayerInputs();
        Map<String,List<String>> graphNodeInputs = configuration.getGraphNodeInputs();

        int nVertices = layerMap.size() + nodeMap.size() + networkInputNames.size();
        this.vertices = new GraphVertex[nVertices];

        //All names: inputs, layers and graph nodes (index to name map)
        Map<String,Integer> allNamesReverse = new HashMap<>();

        int i=0;
        for( String name : networkInputNames){
            GraphVertex gv = new GraphVertex(this,name,i,null);  //Output vertices: set later
            allNamesReverse.put(name,i);
            vertices[i++] = gv;
        }

        numLayers = 0;
        List<Layer> tempLayerList = new ArrayList<>();
        for( Map.Entry<String,Layer> layerEntry : layerMap.entrySet() ){
            Layer l = layerEntry.getValue();
            tempLayerList.add(l);
            InputPreProcessor preProcessor = configuration.getInputPreProcessors().get(layerEntry.getKey());
            String name = layerEntry.getKey();
            GraphVertex gv = new GraphVertex(this,name,i,null,null,l,preProcessor);   //Input and output vertices: set later
            allNamesReverse.put(name,i);
            vertices[i++] = gv;
            numLayers++;
        }
        layers = tempLayerList.toArray(new Layer[numLayers]);

        for( Map.Entry<String,GraphNode> nodeEntry : nodeMap.entrySet() ){
            GraphNode n = nodeEntry.getValue();
            String name = nodeEntry.getKey();
            GraphVertex gv = new GraphVertex(this,name,i,null,null,n);   //Input and output vertices: set later
            allNamesReverse.put(name,i);
            vertices[i++] = gv;
        }

        //Create the lookup table, so we can find vertices easily by name
        verticesMap = new HashMap<>();
        for(GraphVertex gv : vertices){
            verticesMap.put(gv.getVertexName(),gv);
        }

        //Now: do another pass to set the input and output indices...
        //To get output indices: need to essentially build the graph in reverse...
        Map<String,List<String>> verticesOutputTo = new HashMap<>();    //Key: vertex. Values: vertices that this node is an input for
        for( GraphVertex gv : vertices ){
            String vertexName = gv.getVertexName();
            List<String> vertexInputNames;

            if(gv.getLayer() != null){
                //vertex with layer
                vertexInputNames = layerInputs.get(vertexName);
            } else if(gv.getGraphNode() != null){
                //Vertex with node
                vertexInputNames = graphNodeInputs.get(vertexName);

            } else {
                //Input vertex
                vertexInputNames = null;
            }

            if(vertexInputNames == null) continue;

            //Build reverse network structure:
            for(String s : vertexInputNames){
                List<String> list = verticesOutputTo.get(s);
                if(list == null){
                    list = new ArrayList<>();
                    verticesOutputTo.put(s,list);
                }
                list.add(vertexName);   //Edge: s -> vertexName
            }
        }


        for( GraphVertex gv : vertices ){
            String vertexName = gv.getVertexName();
            int vertexIndex = gv.getVertexIndex();
            List<String> vertexInputNames;

            if(gv.getLayer() != null){
                //vertex with layer
                vertexInputNames = layerInputs.get(vertexName);
            } else if(gv.getGraphNode() != null){
                //Vertex with node
                vertexInputNames = graphNodeInputs.get(vertexName);

            } else {
                //Input vertex
                vertexInputNames = null;
            }

            if(vertexInputNames == null) continue;

            VertexIndices[] inputIndices = new VertexIndices[vertexInputNames.size()];
            for( int j=0; j<vertexInputNames.size(); j++ ){
                String inName = vertexInputNames.get(j);
                int inputVertexIndex = allNamesReverse.get(inName);

                //Output of vertex 'inputVertexIndex' is the jth input to the current vertex
                //For input indices, we need to know which output connection of vertex 'inputVertexIndex' this represents
                GraphVertex inputVertex = vertices[inputVertexIndex];
                //First: get the outputs of the input vertex...
                List<String> inputVertexOutputsTo = verticesOutputTo.get(inName);
                int outputNumberOfInput = inputVertexOutputsTo.indexOf(vertexName);


                if(outputNumberOfInput == -1) throw new IllegalStateException("Could not find vertex " + vertexIndex + " in the list of outputs "
                    + "for vertex " + inputVertex + "; error in graph structure?");
                //Overall here: the 'outputNumberOfInput'th output of vertex 'inputVertexIndex' is the jth input to the current vertex

                inputIndices[j] = new VertexIndices(inputVertexIndex,outputNumberOfInput);
            }

            gv.setInputVertices(inputIndices);
        }

        //Handle the outputs for this vertex
        for( GraphVertex gv : vertices ) {
            String vertexName = gv.getVertexName();

            List<String> thisVertexOutputsTo = verticesOutputTo.get(vertexName);

            if(thisVertexOutputsTo == null || thisVertexOutputsTo.size() == 0 ) continue;   //Output vertex
            VertexIndices[] outputIndices = new VertexIndices[thisVertexOutputsTo.size()];
            int j=0;
            for( String s : thisVertexOutputsTo ){
                //First, we have gv -> s
                //Which input in s does gv connect to? s may in general have multiple inputs...
                GraphVertex next = verticesMap.get(s);
                List<String> nextVertexInputNames;
                if(next.hasLayer()) nextVertexInputNames = layerInputs.get(s); //Inputs for vertex (Layer) s
                else nextVertexInputNames = graphNodeInputs.get(s); //TODO checks

                int outputVertexInputNumber = nextVertexInputNames.indexOf(vertexName);

                int outputVertexIndex = allNamesReverse.get(s);
                outputIndices[j++] = new VertexIndices(outputVertexIndex,outputVertexInputNumber);
            }
            gv.setOutputVertices(outputIndices);
        }

        //At this point: each GraphVertex has the local connection structure, both for inputs and outputs
//        for(GraphVertex gv : vertices ){
//            System.out.println(gv);
//        }

        //Given the graph structure, do a topological sort to define forward pass and flattening order:
        topologicalOrder = topologicalSortOrder();
    }

    /** Pretrain network with a single input and single output */
    public void pretrain(DataSetIterator iter){
        if(numInputArrays != 1 || numOutputArrays != 1) throw new UnsupportedOperationException("Cannot train ComputationGraph network with "
            + " multiple inputs or outputs using a DataSetIterator");

        throw new UnsupportedOperationException("Not implemented");
    }

    /** Pretrain network with multiple inputs and/or outputs */
    public void pretrain(Object multipleInputOutputIterator){
        throw new UnsupportedOperationException("Not implemented");
    }

    public void fit(DataSet dataSet){
        if(numInputArrays != 1 || numOutputArrays != 1) throw new UnsupportedOperationException("Cannot train ComputationGraph network with "
                + " multiple inputs or outputs using a DataSet");

        throw new UnsupportedOperationException("Not implemented");
    }

    public void fit(DataSetIterator dataSetIterator){
        if(numInputArrays != 1 || numOutputArrays != 1) throw new UnsupportedOperationException("Cannot train ComputationGraph network with "
                + " multiple inputs or outputs using a DataSetIterator");

        if(configuration.isPretrain()){

            throw new UnsupportedOperationException("Not implemented");
        }

        if(configuration.isBackprop()){
            while(dataSetIterator.hasNext()){
                DataSet next = dataSetIterator.next();
                if (next.getFeatureMatrix() == null || next.getLabels() == null)
                    break;

                boolean hasMaskArrays = next.hasMaskArrays();
                if(hasMaskArrays){
                    throw new UnsupportedOperationException("Training with mask arrays: not yet implemented");
//                    setLayerMaskArrays(next.getFeaturesMaskArray(), next.getLabelsMaskArray());
                }

                if(configuration.getBackpropType() == BackpropType.TruncatedBPTT) {
                    doTruncatedBPTT(new INDArray[]{next.getFeatures()},
                            new INDArray[]{next.getLabels()},
                            (hasMaskArrays ? new INDArray[]{next.getFeaturesMaskArray()} : null),
                            (hasMaskArrays ? new INDArray[]{next.getLabelsMaskArray()} : null));
                }else {
                    setInput(0,next.getFeatureMatrix());
                    setLabel(0,next.getLabels());
                    if( solver == null ){
                        solver = new Solver.Builder()
                                .configure(defaultConfiguration)    //TODO; don't like this
                                .listeners(listeners)
                                .model(this).build();
                    }
                    solver.optimize();
                }

                if(hasMaskArrays){
                    throw new UnsupportedOperationException();
                    //clearLayerMaskArrays();
                }
            }
        }
    }

    public void fit(MultiDataSet multiDataSet){
        if(multiDataSet.hasMaskArrays()){
            throw new UnsupportedOperationException("Training with masking arrays: not yet implemented");
        }
        fit(multiDataSet.getFeatures(),multiDataSet.getLabels());
    }

    public void fit(MultiDataSetIterator multiDataSetIterator){
        if(configuration.isPretrain()){

            throw new UnsupportedOperationException("Pretraining not yet implemented");
        }

        if(configuration.isBackprop()){
            while(multiDataSetIterator.hasNext()){
                MultiDataSet next = multiDataSetIterator.next();
                if (next.getFeatures() == null || next.getLabels() == null)
                    break;

                boolean hasMaskArrays = next.hasMaskArrays();
                if(hasMaskArrays){
                    throw new UnsupportedOperationException("Not yet implemented");
//                    setLayerMaskArrays(next.getFeaturesMaskArray(), next.getLabelsMaskArray());
                }

                if(configuration.getBackpropType() == BackpropType.TruncatedBPTT) {
                    doTruncatedBPTT(next.getFeatures(),next.getLabels(),next.getFeaturesMaskArrays(), next.getLabelsMaskArrays());
                } else {
                    setInputs(next.getFeatures());
                    setLabels(next.getLabels());
                    if( solver == null ){
                        solver = new Solver.Builder()
                                .configure(defaultConfiguration)    //TODO; don't like this
                                .listeners(listeners)
                                .model(this).build();
                    }
                    solver.optimize();
                }

                if(hasMaskArrays){
                    throw new UnsupportedOperationException();
                    //clearLayerMaskArrays();
                }
            }
        }
    }

    public void fit(INDArray[] inputs, INDArray[] labels ){
        setInputs(inputs);
        setLabels(labels);


        if(configuration.isPretrain()){

            throw new UnsupportedOperationException("Not implemented");
        }

        if(configuration.isBackprop()){
            if(configuration.getBackpropType() == BackpropType.TruncatedBPTT){
                doTruncatedBPTT(inputs,labels,null,null);
            } else {
                if( solver == null) {
                    solver = new Solver.Builder()
                            .configure(conf())
                            .listeners(getListeners())
                            .model(this).build();
                }

                solver.optimize();
            }
        }
    }

    /** Calculate a topological sort order for the vertices in the graph.
     * Note that this is used for
     * (a) working out what order to do forward pass,
     * (b) what order to do backprop (i.e., reverse of this)
     * (c) order to flatten parameters (and gradients)
     *  */
    public int[] topologicalSortOrder(){
        if(topologicalOrder != null) return topologicalOrder;

        //https://en.wikipedia.org/wiki/Topological_sorting#Kahn.27s_algorithm
        int[] out = new int[vertices.length];
        int outCounter = 0;

        //First: represent the graph more usefully as a Map<Integer,Set<Integer>>, where map represents edges i -> j
        // key represents j, set is set of i (inputs) for vertices j
        Map<Integer,Set<Integer>> inputEdges = new HashMap<>();
        for(GraphVertex gv : vertices){
            VertexIndices[] vertexInputsFrom = gv.getInputVertices();
            if(vertexInputsFrom == null || vertexInputsFrom.length == 0){
                inputEdges.put(gv.getIndex(),null);
                continue;
            }
            Set<Integer> set = new HashSet<>();
            for( VertexIndices v : vertexInputsFrom ){
                set.add(v.getVertexIndex());
            }
            inputEdges.put(gv.getVertexIndex(),set);
        }

        //Now: do topological sort
        //Set of all nodes with no incoming edges: (this would be: input vertices)
        LinkedList<Integer> noIncomingEdges = new LinkedList<>();
        for( Map.Entry<Integer,Set<Integer>> entry : inputEdges.entrySet() ) {
            Set<Integer> inputsFrom = entry.getValue();
            if(inputsFrom == null || inputsFrom.size() == 0) {
                noIncomingEdges.add(entry.getKey());
            }
        }

        while(noIncomingEdges.size() > 0) {
            int next = noIncomingEdges.removeFirst();
            out[outCounter++] = next;   //Add to sorted list

            VertexIndices[] vertexOutputsTo = vertices[next].getOutputVertices();  //Edges: next -> vertexOutpusTo[...]
            //Remove edges next -> vertexOuputsTo[...] from graph;
            if(vertexOutputsTo != null ) {
                for (VertexIndices v : vertexOutputsTo) {
                    Set<Integer> set = inputEdges.get(v.getVertexIndex());
                    set.remove(next);
                    if (set.size() == 0) {
                        noIncomingEdges.add(v.getVertexIndex()); //No remaining edges for vertex i -> add to list for processing
                    }
                }
            }
        }

        //If any edges remain in the graph: graph has cycles:
        for(Map.Entry<Integer,Set<Integer>> entry : inputEdges.entrySet()){
            Set<Integer> set = entry.getValue();
            if(set == null) continue;
            if(set.size() > 0) throw new IllegalStateException("Invalid configuration: cycle detected in graph. Cannot calculate topological ordering with graph cycle ("
                + "cycle includes vertex \"" + vertices[entry.getKey()].getVertexName() + "\")");
        }

        return out;
    }


    @Override
    public void computeGradientAndScore() {
        //Calculate activations (which are stored in each layer, and used in backprop)
        if(configuration.getBackpropType() == BackpropType.TruncatedBPTT) {
            rnnActivateUsingStoredState(inputs, true, true);
            backprop(true);
        }
        else {
            feedForward(true);
            backprop(false);
        }

        //Score: sum of the scores for the various output layers...
        double l1 = calcL1();
        double l2 = calcL2();

        score = 0.0;
        for(String s : configuration.getNetworkOutputs()){
            GraphVertex gv = verticesMap.get(s);

            score += ((BaseOutputLayer<?>)gv.getLayer()).computeScore(l1,l2,true);

            //Only want to add l1/l2 once...
            l1 = 0.0;
            l2 = 0.0;
        }
    }

    public Map<String,INDArray> feedForward(INDArray input, boolean train){
        if(numInputArrays != 1) throw new UnsupportedOperationException("Cannot feedForward with single input for graph network with " + numInputArrays + " expected inputs");
        setInput(0,input);
        return feedForward(train);
    }

    public Map<String,INDArray> feedForward(INDArray[] input, boolean train){
        if(numInputArrays != input.length) throw new UnsupportedOperationException("Cannot feedForward with " + input.length + " inputs for graph network with " + numInputArrays + " expected inputs");
        for( int i=0; i<input.length; i++ ) setInput(i,input[i]);
        return feedForward(train);
    }

    public Map<String,INDArray> feedForward(boolean train){
        Map<String,INDArray> layerActivations = new HashMap<>();

        //Do forward pass according to the topological ordering of the network
        for( int i=0; i<topologicalOrder.length; i++ ){
            GraphVertex current = vertices[topologicalOrder[i]];
            if(current.isInputVertex()){
                VertexIndices[] inputsTo = current.getOutputVertices();
                INDArray input = inputs[current.getIndex()];

                layerActivations.put(current.getVertexName(),input);

                for( VertexIndices v : inputsTo ){
                    int vIdx = v.getVertexIndex();
                    int vIdxInputNum = v.getVertexEdgeNumber();
                    //This input: the 'vIdxInputNum'th input to vertex 'vIdx'
                    vertices[vIdx].setInput(vIdxInputNum,input.dup());  //TODO When to dup?
                }

            } else {
                //Do forward pass:
                INDArray out = current.doForward(train);

                if(current.hasLayer()){
                    layerActivations.put(current.getVertexName(),out);
                }

                //Now, set the inputs for the next vertices:
                VertexIndices[] outputsTo = current.getOutputVertices();
                if(outputsTo != null) {
                    int j = 0;
                    for (VertexIndices v : outputsTo) {
                        int vIdx = v.getVertexIndex();
                        int inputNum = v.getVertexEdgeNumber();
                        //This (jth) connection from the output: is the 'inputNum'th input to vertex 'vIdx'
                        vertices[vIdx].setInput(inputNum, out);
                    }
                }

            }
        }

        return layerActivations;
    }

    /**
     * @param truncatedBPTT false: normal backprop. true: calculate gradients using truncated BPTT for RNN layers
     */
    protected void backprop(boolean truncatedBPTT){
        LinkedList<Pair<String,INDArray>> gradients = new LinkedList<>();

        //Do backprop according to the reverse of the topological ordering of the network
        for( int i=topologicalOrder.length-1; i>= 0; i-- ){
            GraphVertex current = vertices[topologicalOrder[i]];

            if(current.isInputVertex()) continue;   //No op

            if(current.isOutputVertex()){
                BaseOutputLayer<?> outputLayer = (BaseOutputLayer<?>)current.getLayer();

                int thisOutputNumber = configuration.getNetworkOutputs().indexOf(current.getVertexName());  //TODO more efficient way
                INDArray currLabels = labels[thisOutputNumber];
                outputLayer.setLabels(currLabels);
            }

            Pair<Gradient,INDArray[]> pair = current.doBackward(truncatedBPTT,(truncatedBPTT ? configuration.getTbpttBackLength() : 0));
            INDArray[] epsilons = pair.getSecond();

            //Inputs to the current GraphVertex:
            VertexIndices[] inputVertices = current.getInputVertices();

            //Set epsilons for the input vertices:
            if(inputVertices != null ){
                int j=0;
                for(VertexIndices v : inputVertices){
                    GraphVertex gv = vertices[v.getVertexIndex()];
                    int outputNumberOfInputVertex = v.getVertexEdgeNumber();
                    gv.setError(outputNumberOfInputVertex,epsilons[j++]); //TODO check this
                }
            }

            if(pair.getFirst() != null){
                Gradient g = pair.getFirst();
                Map<String,INDArray> map = g.gradientForVariable();
                LinkedList<Pair<String,INDArray>> tempList = new LinkedList<>();
                for( Map.Entry<String,INDArray> entry : map.entrySet() ){
                    String newName = current.getVertexName() + "_" + entry.getKey();
                    tempList.addFirst(new Pair<>(newName,entry.getValue()));
                }
                for(Pair<String,INDArray> p : tempList ) gradients.addFirst(p);
            }


        }

        //Now, add the gradients in the order we need them in for flattening (same as params order)
        Gradient gradient = new DefaultGradient();
        for(Pair<String,INDArray> p : gradients ){
            gradient.setGradientFor(p.getFirst(),p.getSecond());
        }

        this.gradient = gradient;
    }

    @Override
    public ComputationGraph clone(){

        ComputationGraph cg = new ComputationGraph(configuration.clone());
        cg.init();
        cg.setParams(params().dup());

        return cg;
    }

    public double calcL2() {
        double l2 = 0.0;
        for(GraphVertex gv : vertices){
            if(gv.hasLayer()){
                l2 += gv.getLayer().calcL2();
            }
        }
        return l2;
    }

    public double calcL1() {
        double l1 = 0.0;
        for(GraphVertex gv : vertices){
            if(gv.hasLayer()){
                l1 += gv.getLayer().calcL1();
            }
        }
        return l1;
    }

    public void setListeners(Collection<IterationListener> listeners){
        this.listeners = listeners;
        if(layers == null) init();

        for( Layer l : layers){
            l.setListeners();
        }
    }

    public void setListeners(IterationListener... listeners){
        List<IterationListener> list = new ArrayList<>();
        Collections.addAll(list,listeners);
        setListeners(list);
    }

    public Collection<IterationListener> getListeners(){
        return listeners;
    }

    public ComputationGraphUpdater getUpdater(){
        if(solver == null){
            solver = new Solver.Builder()
                    .configure(conf())
                    .listeners(getListeners())
                    .model(this).build();
            solver.getOptimizer().setUpdaterComputationGraph(new ComputationGraphUpdater(this));
        }
        return solver.getOptimizer().getComputationGraphUpdater();
    }

    public void setUpdater(ComputationGraphUpdater updater){
        if(solver == null){
            solver = new Solver.Builder()
                    .configure(conf())
                    .listeners(getListeners())
                    .model(this).build();
        }
        solver.getOptimizer().setUpdaterComputationGraph(updater);
    }

    public Layer getInputLayer( int inputLayerIdx ){
        if(inputLayerIdx >= numInputArrays) throw new IllegalArgumentException("Invalid index: cannot get input layer "
                + inputLayerIdx + ", total number of network inputs = " + numInputArrays);
        return getLayer(configuration.getNetworkInputs().get(inputLayerIdx));
    }

    public Layer getOutputLayer(int outputLayerIdx ){
        if(outputLayerIdx >= numOutputArrays ) throw new IllegalArgumentException("Invalid index: cannot get output layer "
                + outputLayerIdx + ", total number of network outputs = " + numOutputArrays);
        return getLayer(configuration.getNetworkOutputs().get(outputLayerIdx));
    }

    public INDArray params(boolean backwardOnly){
        List<INDArray> list = new ArrayList<>(layerCount);
        for( int i=0; i<topologicalOrder.length; i++ ){
            if(!vertices[topologicalOrder[i]].hasLayer()) continue;

            Layer l = vertices[topologicalOrder[i]].getLayer();
            if(backwardOnly && l instanceof BasePretrainNetwork ){
                list.add(((BasePretrainNetwork)l).paramsBackprop());
            } else {
                list.add(l.params());
            }
        }

        return Nd4j.toFlattened('f', list);
    }

    public double score(DataSet dataSet){
        return score(dataSet, false);
    }

    public double score(DataSet dataSet, boolean training){

        throw new UnsupportedOperationException("Not yet implemented");
    }

    public double score(MultiDataSet dataSet){
        return score(dataSet,false);
    }

    public double score(MultiDataSet dataSet, boolean training){

        throw new UnsupportedOperationException("Not yet implemented");
    }



    //------------------------------------------------------
    //Model methods:

    @Override
    public void fit() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void update(INDArray gradient, String paramType) {
        throw new UnsupportedOperationException("Not implemnted");
    }

    @Override
    public double score() {
        return score;
    }

    @Override
    public void accumulateScore(double accum) {
        throw new UnsupportedOperationException("Not implemnted");
    }

    @Override
    public INDArray params() {
        return params(false);
    }

    @Override
    public int numParams() {
        return numParams(false);
    }

    @Override
    public int numParams(boolean backwards) {
        int nParams = 0;
        for( int i=0; i<layers.length; i++ ){
            nParams += layers[i].numParams(backwards);
        }
        return nParams;
    }

    @Override
    public void setParams(INDArray params) {
        int idx = 0;
        for( int i=0; i<topologicalOrder.length; i++ ){
            if(!vertices[topologicalOrder[i]].hasLayer()) continue;

            Layer layer = vertices[topologicalOrder[i]].getLayer();
            int range = (layer instanceof BasePretrainNetwork ?
                    ((BasePretrainNetwork<?>)layer).numParamsBackprop() : layer.numParams());
            INDArray get = params.get(NDArrayIndex.point(0),NDArrayIndex.interval(idx, range + idx));
            layer.setParams(get);
            idx += range;
        }
    }

    @Override
    public void applyLearningRateScoreDecay() {
        throw new UnsupportedOperationException("Not implemnted");
    }

    @Override
    public void fit(INDArray data) {
        throw new UnsupportedOperationException("Not implemnted");
    }

    @Override
    public void iterate(INDArray input) {
        throw new UnsupportedOperationException("Not implemnted");
    }

    @Override
    public Gradient gradient() {
        return gradient;
    }

    @Override
    public Pair<Gradient, Double> gradientAndScore() {
        return new Pair<>(gradient(),score());
    }

    @Override
    public int batchSize() {
        //TODO: check this. Will this work in general, for all cases?
        return inputs[0].size(0);
    }

    @Override
    public NeuralNetConfiguration conf() {
        return defaultConfiguration;
    }

    @Override
    public void setConf(NeuralNetConfiguration conf) {
        throw new UnsupportedOperationException("Not implemnted");
    }

    @Override
    public INDArray input() {
        throw new UnsupportedOperationException("Not implemnted");
    }

    @Override
    public void validateInput() {
        //TODO
    }

    @Override
    public ConvexOptimizer getOptimizer() {
        throw new UnsupportedOperationException("Not implemnted");
    }

    @Override
    public INDArray getParam(String param) {
        throw new UnsupportedOperationException("Not implemnted");
    }

    @Override
    public void initParams() {
        throw new UnsupportedOperationException("Not implemnted");
    }

    @Override
    public Map<String, INDArray> paramTable() {
        //Get all parameters from all layers
        Map<String,INDArray> allParams = new LinkedHashMap<>();
        for( int i=0; i<layers.length; i++ ){
            Map<String,INDArray> paramMap = layers[i].paramTable();
            for( Map.Entry<String, INDArray> entry : paramMap.entrySet() ){
                String newKey = layers[i].conf().getLayer().getLayerName() + "_" + entry.getKey();
                allParams.put(newKey, entry.getValue());
            }
        }
        return allParams;
    }

    @Override
    public void setParamTable(Map<String, INDArray> paramTable) {
        throw new UnsupportedOperationException("Not implemnted");
    }

    @Override
    public void setParam(String key, INDArray val) {
        throw new UnsupportedOperationException("Not implemnted");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("Not implemnted");
    }

    //------------------------------------------------------------------------------
    //RNN-specific functionality

    /**If this ComputationGraph contains one or more RNN layers: conduct forward pass (prediction)
     * but using previous stored state for any RNN layers. The activations for the final step are
     * also stored in the RNN layers for use next time rnnTimeStep() is called.<br>
     * This method can be used to generate output one or more steps at a time instead of always having to do
     * forward pass from t=0. Example uses are for streaming data, and for generating samples from network output
     * one step at a time (where samples are then fed back into the network as input)<br>
     * If no previous state is present in RNN layers (i.e., initially or after calling rnnClearPreviousState()),
     * the default initialization (usually 0) is used.<br>
     * Supports mini-batch (i.e., multiple predictions/forward pass in parallel) as well as for single examples.<br>
     * @param inputs Input to network. May be for one or multiple time steps. For single time step:
     *  input has shape [miniBatchSize,inputSize] or [miniBatchSize,inputSize,1]. miniBatchSize=1 for single example.<br>
     *  For multiple time steps: [miniBatchSize,inputSize,inputTimeSeriesLength]
     * @return Output activations. If output is RNN layer (such as RnnOutputLayer): if all inputs have shape [miniBatchSize,inputSize]
     * i.e., is 2d, then outputs have shape [miniBatchSize,outputSize] (i.e., also 2d) instead of [miniBatchSize,outputSize,1].<br>
     * Otherwise output is 3d [miniBatchSize,outputSize,inputTimeSeriesLength] when using RnnOutputLayer (or unmodified otherwise).
     */
    public INDArray[] rnnTimeStep(INDArray... inputs){
        //Idea: if 2d in, want 2d out
        boolean inputIs2d = true;
        for(INDArray i : inputs){
            if(i.rank() != 2){
                inputIs2d = false;
                break;
            }
        }

        INDArray[] outputs = new INDArray[this.numOutputArrays];

        //Based on: feedForward()
        for (int currVertexIdx : topologicalOrder) {
            GraphVertex current = vertices[currVertexIdx];
            if (current.isInputVertex()) {
                VertexIndices[] inputsTo = current.getOutputVertices();
                INDArray input = inputs[current.getIndex()];

                for (VertexIndices v : inputsTo) {
                    int vIdx = v.getVertexIndex();
                    int vIdxInputNum = v.getVertexEdgeNumber();
                    //This input: the 'vIdxInputNum'th input to vertex 'vIdx'
                    vertices[vIdx].setInput(vIdxInputNum, input.dup());  //TODO When to dup?
                }

            } else {
                INDArray out;
                if(current.hasLayer()){
                    //Layer
                    Layer l = current.getLayer();
                    if (l instanceof BaseRecurrentLayer<?>) {
                        out = ((BaseRecurrentLayer<?>) l).rnnTimeStep(current.getInputs()[0]);
                    } else if (l instanceof MultiLayerNetwork) {
                        out = ((MultiLayerNetwork) l).rnnTimeStep(current.getInputs()[0]);
                    } else {
                        //non-recurrent layer
                        out = current.doForward(false);
                    }
                } else {
                    //GraphNode
                    out = current.doForward(false);
                }

                if(current.isOutputVertex()){
                    //Get the index of this output vertex...
                    int idx = configuration.getNetworkOutputs().indexOf(current.getVertexName());
                    outputs[idx] = out;
                }

                //Now, set the inputs for the next vertices:
                VertexIndices[] outputsTo = current.getOutputVertices();
                if (outputsTo != null) {
                    for (VertexIndices v : outputsTo) {
                        int vIdx = v.getVertexIndex();
                        int inputNum = v.getVertexEdgeNumber();
                        //This (jth) connection from the output: is the 'inputNum'th input to vertex 'vIdx'
                        vertices[vIdx].setInput(inputNum, out);
                    }
                }
            }
        }

        //As per MultiLayerNetwork.rnnTimeStep(): if inputs are all 2d, then outputs are all 2d
        if(inputIs2d){
            for( int i=0; i<outputs.length; i++ ){
                if(outputs[i].rank() == 3 && outputs[i].size(2) == 1){
                    //Return 2d output with shape [miniBatchSize,nOut]
                    // instead of 3d output with shape [miniBatchSize,nOut,1]
                    outputs[i] = outputs[i].tensorAlongDimension(0,1,0);
                }
            }
        }

        return outputs;
    }

    /**Get the state of the RNN layer, as used in {@link #rnnTimeStep(INDArray...)}.
     * @param layer Number/index of the layer.
     * @return Hidden state, or null if layer is not an RNN layer
     */
    public Map<String,INDArray> rnnGetPreviousState(int layer){
        return rnnGetPreviousState(layers[layer].conf().getLayer().getLayerName());
    }

    /**Get the state of the RNN layer, as used in {@link #rnnTimeStep(INDArray...)}.
     * @param layerName name of the layer
     * @return Hidden state, or null if layer is not an RNN layer
     */
    public Map<String,INDArray> rnnGetPreviousState(String layerName){
        Layer l = verticesMap.get(layerName).getLayer();
        if(l == null || !(l instanceof BaseRecurrentLayer<?>)) return null;
        return ((BaseRecurrentLayer<?>)l).rnnGetPreviousState();
    }

    /**Get a map of states for ALL RNN layers, as used in {@link #rnnTimeStep(INDArray...)}.
     * Layers that are not RNN layers will not have an entry in the returned map
     * @return Map of states (keyed by layer name) or null if layer is not an RNN layer
     * @see #rnnSetPreviousStates(Map)
     */
    public Map<String,Map<String,INDArray>> rnnGetPreviousStates(){
        Map<String,Map<String,INDArray>> states = new HashMap<>();
        for(Layer l : layers){
            if(l instanceof BaseRecurrentLayer<?>){
                states.put(l.conf().getLayer().getLayerName(), ((BaseRecurrentLayer<?>)l).rnnGetPreviousState());
            }
        }
        return states;
    }

    /**Set the state of the RNN layer, for use in {@link #rnnTimeStep(INDArray...)}
     * @param layer The number/index of the layer.
     * @param state The state to set the specified layer to
     */
    public void rnnSetPreviousState(int layer, Map<String,INDArray> state){
        rnnSetPreviousState(layers[layer].conf().getLayer().getLayerName(), state);
    }

    /**Set the state of the RNN layer, for use in {@link #rnnTimeStep(INDArray...)}
     * @param layerName The name of the layer.
     * @param state The state to set the specified layer to
     */
    public void rnnSetPreviousState(String layerName, Map<String,INDArray> state){
        Layer l = verticesMap.get(layerName).getLayer();
        if(l == null || !(l instanceof BaseRecurrentLayer<?>)){
            throw new UnsupportedOperationException("Layer \"" + layerName + "\" is not a recurrent layer. Cannot set state");
        }
        ((BaseRecurrentLayer<?>)l).rnnSetPreviousState(state);
    }

    /** Set the states for all RNN layers, for use in {@link #rnnTimeStep(INDArray...)}
     * @param previousStates The previous time step states for all layers (key: layer name. Value: layer states)
     * @see #rnnGetPreviousStates()
     */
    public void rnnSetPreviousStates(Map<String,Map<String,INDArray>> previousStates){
        for(Map.Entry<String,Map<String,INDArray>> entry : previousStates.entrySet()){
            rnnSetPreviousState(entry.getKey(),entry.getValue());
        }
    }

    /** Clear the previous state of the RNN layers (if any), used in {@link #rnnTimeStep(INDArray...)}
     */
    public void rnnClearPreviousState(){
        if( layers == null ) return;
        for (Layer layer : layers) {
            if (layer instanceof BaseRecurrentLayer) ((BaseRecurrentLayer<?>) layer).rnnClearPreviousState();
            else if (layer instanceof MultiLayerNetwork) {
                ((MultiLayerNetwork) layer).rnnClearPreviousState();
            }
        }
    }

    protected void doTruncatedBPTT(INDArray[] inputs, INDArray[] labels, INDArray[] featureMasks, INDArray[] labelMasks ){

        //Approach used here to implement truncated BPTT: if input is 3d, split it. Otherwise: input is unmodified

        int timeSeriesLength = -1;
        for(INDArray in : inputs){
            if(in.rank() != 3) continue;
            if(timeSeriesLength == -1) timeSeriesLength = in.size(2);
            else if(timeSeriesLength != in.size(2)){
                log.warn("Cannot do TBPTT with time series of different lengths");
                return;
            }
        }
        for(INDArray out : labels){
            if(out.rank() != 3) continue;
            if(timeSeriesLength == -1) timeSeriesLength = out.size(2);
            else if(timeSeriesLength != out.size(2)){
                log.warn("Cannot do TBPTT with time series of different lengths");
                return;
            }
        }

        int fwdLen = configuration.getTbpttFwdLength();
        if(fwdLen > timeSeriesLength) {
            log.warn("Cannot do TBPTT: Truncated BPTT forward length (" + fwdLen + ") > input time series length (" + timeSeriesLength + ")");
            return;
        }

        int nSubsets = timeSeriesLength / fwdLen;

        rnnClearPreviousState();

        INDArray[] newInputs = new INDArray[inputs.length];
        INDArray[] newLabels = new INDArray[labels.length];
        INDArray[] newFeatureMasks = (featureMasks != null ? new INDArray[featureMasks.length] : null);
        INDArray[] newLabelMasks = (labelMasks != null ? new INDArray[labelMasks.length] : null);

        for( int i=0; i<nSubsets; i++ ){
            int startTimeIdx = i*fwdLen;
            int endTimeIdx = startTimeIdx + fwdLen;

            for( int j=0; j< inputs.length; j++ ){
                if(inputs[j].rank() != 3) newInputs[j] = inputs[j];
                else {
                    newInputs[j] = inputs[j].get(NDArrayIndex.all(),NDArrayIndex.all(),NDArrayIndex.interval(startTimeIdx, endTimeIdx));
                }
            }
            for( int j=0; j<labels.length; j++ ){
                if(labels[j].rank() != 3) newLabels[j] = labels[j];
                else {
                    newLabels[j] = labels[j].get(NDArrayIndex.all(),NDArrayIndex.all(),NDArrayIndex.interval(startTimeIdx, endTimeIdx));
                }
            }
            if(featureMasks != null){
                for( int j=0; j<featureMasks.length; j++ ){
                    if(featureMasks[j] == null) continue;
                    newFeatureMasks[j] = featureMasks[j].get(NDArrayIndex.all(), NDArrayIndex.interval(startTimeIdx,endTimeIdx));
                }
            }
            if(labelMasks != null){
                for( int j=0; j<labelMasks.length; j++ ){
                    if(labelMasks[j] == null) continue;
                    newLabelMasks[j] = labelMasks[j].get(NDArrayIndex.all(), NDArrayIndex.interval(startTimeIdx,endTimeIdx));
                }
            }

            setInputs(newInputs);
            setLabels(newLabels);
            setLayerMaskArrays(newFeatureMasks,newLabelMasks);

            if(solver == null) {
                solver = new Solver.Builder()
                        .configure(conf())
                        .listeners(getListeners())
                        .model(this).build();
            }
            solver.optimize();

            //Finally, update the state of the RNN layers:
            rnnUpdateStateWithTBPTTState();
        }

        rnnClearPreviousState();
    }

    /** Similar to rnnTimeStep and feedForward() methods. Difference here is that this method:<br>
     * (a) like rnnTimeStep does forward pass using stored state for RNN layers, and<br>
     * (b) unlike rnnTimeStep does not modify the RNN layer state<br>
     * Therefore multiple calls to this method with the same input should have the same output.<br>
     * Typically used during training only. Use rnnTimeStep for prediction/forward pass at test time.
     * @param inputs Input to network
     * @param training Whether training or not
     * @param storeLastForTBPTT set to true if used as part of truncated BPTT training
     * @return Activations for each layer (including input, as per feedforward() etc)
     */
    public Map<String,INDArray> rnnActivateUsingStoredState(INDArray[] inputs, boolean training, boolean storeLastForTBPTT) {
        Map<String,INDArray> layerActivations = new HashMap<>();

        //Do forward pass according to the topological ordering of the network
        for (int currVertexIdx : topologicalOrder) {
            GraphVertex current = vertices[currVertexIdx];
            if (current.isInputVertex()) {
                VertexIndices[] inputsTo = current.getOutputVertices();
                INDArray input = inputs[current.getIndex()];

                layerActivations.put(current.getVertexName(), input);

                for (VertexIndices v : inputsTo) {
                    int vIdx = v.getVertexIndex();
                    int vIdxInputNum = v.getVertexEdgeNumber();
                    //This input: the 'vIdxInputNum'th input to vertex 'vIdx'
                    vertices[vIdx].setInput(vIdxInputNum, input.dup());  //TODO When to dup?
                }

            } else {
                INDArray out;
                if(current.hasLayer()){
                    Layer l = current.getLayer();
                    if (l instanceof BaseRecurrentLayer<?>) {
                        out = ((BaseRecurrentLayer<?>) l).rnnActivateUsingStoredState(current.getInputs()[0],training,storeLastForTBPTT);
                    } else if (l instanceof MultiLayerNetwork) {
                        List<INDArray> temp = ((MultiLayerNetwork) l).rnnActivateUsingStoredState(current.getInputs()[0],training,storeLastForTBPTT);
                        out = temp.get(temp.size()-1);
                    } else {
                        //non-recurrent layer
                        out = current.doForward(training);
                    }
                    layerActivations.put(current.getVertexName(), out);
                } else {
                    out = current.doForward(training);
                }

                //Now, set the inputs for the next vertices:
                VertexIndices[] outputsTo = current.getOutputVertices();
                if (outputsTo != null) {
                    for (VertexIndices v : outputsTo) {
                        int vIdx = v.getVertexIndex();
                        int inputNum = v.getVertexEdgeNumber();
                        //This (jth) connection from the output: is the 'inputNum'th input to vertex 'vIdx'
                        vertices[vIdx].setInput(inputNum, out);
                    }
                }
            }
        }

        return layerActivations;
    }

    public void setLayerMaskArrays(INDArray[] featureMaskArrays, INDArray[] labelMaskArrays){
        //Complication with mask arrays: dense layers before recurrent layers: need to be masked

        if(featureMaskArrays != null){
            if(featureMaskArrays.length != numInputArrays){
                throw new IllegalArgumentException("Invalid number of feature mask arrays");
            }
            for( int i=0; i<featureMaskArrays.length; i++ ){
                String inputName = configuration.getNetworkInputs().get(i);

                //feedforward layers below a RNN layer: need the input (features) mask
                //Reason: even if the time series input is zero padded, the output from the dense layers are
                // non-zero (i.e., activationFunction(0*weights + bias) != 0 in general)
                //This assumes that the time series input is masked - i.e., values are 0 at the padded time steps,
                // so we don't need to do anything for the recurrent layer

                //TODO: complication. What if input masking doesn't match between input masks?
                //Does this

            }
        }

        if(labelMaskArrays != null) {
            if(labelMaskArrays.length != numInputArrays){
                throw new IllegalArgumentException("Invalid number of label mask arrays");
            }
            for( int i=0; i<labelMaskArrays.length; i++ ){
                String outputName = configuration.getNetworkOutputs().get(i);
                GraphVertex v = verticesMap.get(outputName);
                Layer ol = v.getLayer();
                ol.setMaskArray(labelMaskArrays[i]);
            }
        }


        throw new UnsupportedOperationException("Not yet implemented");
    }

    /** Remove the mask arrays from all layers.<br>
     * See {@link #setLayerMaskArrays(INDArray[], INDArray[])} for details on mask arrays.
     */
    public void clearLayerMaskArrays(){
        for (Layer layer : layers) {
            layer.setMaskArray(null);
        }
    }

    protected void rnnUpdateStateWithTBPTTState(){

        throw new UnsupportedOperationException("Not yet implemented");
    }


}
