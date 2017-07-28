package com.ibm.streamsx.topology.internal.embedded;

import static com.ibm.streams.operator.Type.MetaType.BOOLEAN;
import static com.ibm.streams.operator.Type.MetaType.DECIMAL128;
import static com.ibm.streams.operator.Type.MetaType.FLOAT32;
import static com.ibm.streams.operator.Type.MetaType.FLOAT64;
import static com.ibm.streams.operator.Type.MetaType.INT16;
import static com.ibm.streams.operator.Type.MetaType.INT32;
import static com.ibm.streams.operator.Type.MetaType.INT64;
import static com.ibm.streams.operator.Type.MetaType.INT8;
import static com.ibm.streams.operator.Type.MetaType.RSTRING;
import static com.ibm.streams.operator.Type.MetaType.UINT16;
import static com.ibm.streams.operator.Type.MetaType.UINT32;
import static com.ibm.streams.operator.Type.MetaType.UINT64;
import static com.ibm.streams.operator.Type.MetaType.UINT8;
import static com.ibm.streams.operator.Type.MetaType.USTRING;
import static com.ibm.streamsx.topology.builder.JParamTypes.TYPE_ATTRIBUTE;
import static com.ibm.streamsx.topology.generator.operator.OpProperties.KIND;
import static com.ibm.streamsx.topology.generator.operator.OpProperties.KIND_CLASS;
import static com.ibm.streamsx.topology.generator.operator.OpProperties.LANGUAGE;
import static com.ibm.streamsx.topology.generator.operator.OpProperties.LANGUAGE_JAVA;
import static com.ibm.streamsx.topology.generator.operator.OpProperties.MODEL;
import static com.ibm.streamsx.topology.generator.operator.OpProperties.MODEL_FUNCTIONAL;
import static com.ibm.streamsx.topology.generator.operator.OpProperties.MODEL_SPL;
import static com.ibm.streamsx.topology.generator.operator.OpProperties.MODEL_VIRTUAL;
import static com.ibm.streamsx.topology.internal.graph.GraphKeys.NAME;
import static com.ibm.streamsx.topology.internal.graph.GraphKeys.NAMESPACE;
import static com.ibm.streamsx.topology.internal.gson.GsonUtilities.jisEmpty;
import static com.ibm.streamsx.topology.internal.gson.GsonUtilities.jstring;
import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.ibm.streams.flow.declare.InputPortDeclaration;
import com.ibm.streams.flow.declare.OperatorGraph;
import com.ibm.streams.flow.declare.OperatorGraphFactory;
import com.ibm.streams.flow.declare.OperatorInvocation;
import com.ibm.streams.flow.declare.OutputPortDeclaration;
import com.ibm.streams.operator.Operator;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.Type;
import com.ibm.streamsx.topology.builder.BOperator;
import com.ibm.streamsx.topology.builder.GraphBuilder;
import com.ibm.streamsx.topology.builder.JParamTypes;
import com.ibm.streamsx.topology.context.StreamsContext;
import com.ibm.streamsx.topology.internal.gson.GsonUtilities;
import com.ibm.streamsx.topology.internal.json4j.JSON4JUtilities;

/**
 * Takes the JSON graph defined by Topology
 * and creates an OperatorGraph for embedded use.
 * 
 * TODO - work in progress - currently just collects the operator decls.
 *
 */
public class EmbeddedGraph {
    
    private final GraphBuilder builder;
    private OperatorGraph graphDecl;
    
    private final Map<String,OutputPortDeclaration> outputPorts = new HashMap<>();
    private final Map<String,InputPortDeclaration> inputPorts = new HashMap<>();
    
    
    public static void verifySupported(GraphBuilder builder) {
        new EmbeddedGraph(builder).verifySupported();
    }
   
    public EmbeddedGraph(GraphBuilder builder)  {
        this.builder = builder;
    }
    
    public void verifySupported() {        
        for (BOperator op : builder.getOps())
            verifyOp(op);
    }
    
    private boolean verifyOp(BOperator op) {
        JsonObject json = JSON4JUtilities.gson(op.complete());
        
        switch (jstring(json, MODEL)) {
        case MODEL_VIRTUAL:
            return false;
        case MODEL_FUNCTIONAL:
        case MODEL_SPL:
            if (!LANGUAGE_JAVA.equals(jstring(json, LANGUAGE)))
                throw notSupported(op);
            return true;
        default:
            throw notSupported(op);
        }
    }
    
    public OperatorGraph declareGraph() throws Exception {
        graphDecl = OperatorGraphFactory.newGraph();
        
        declareOps();
        
        declareConnections();
                
        return graphDecl;
    }

    private void declareOps() throws Exception {
        for (BOperator op : builder.getOps())
            declareOp(op);
    }
    
    /**
     * Creates the complete operator declaration
     * from the JSON representation.
     * @param op
     * @throws Exception
     */
    @SuppressWarnings("unchecked")
    private void declareOp(BOperator op) throws Exception {
        JsonObject json = JSON4JUtilities.gson(op.complete());
       
        if (!verifyOp(op))
            return;
        
        String opClassName = jstring(json, KIND_CLASS);
        Class<? extends Operator> opClass = (Class<? extends Operator>) Class.forName(opClassName);
        OperatorInvocation<? extends Operator> opDecl = graphDecl.addOperator(opClass);
        
        if (json.has("parameters")) {
            JsonObject params = json.getAsJsonObject("parameters");
            for (Entry<String, JsonElement> param : params.entrySet())
                setOpParameter(opDecl, param.getKey(), param.getValue().getAsJsonObject());
        }
        
        declareOutputs(opDecl, json.getAsJsonArray("outputs"));
        declareInputs(opDecl, json.getAsJsonArray("inputs"));
    }
    
    private void declareOutputs(OperatorInvocation<? extends Operator> opDecl, JsonArray outputs) {
        if (GsonUtilities.jisEmpty(outputs))
            return;
        
        // Ensure we deal with them in port order.
        JsonObject[] ports = new JsonObject[outputs.size()];
        for (JsonElement e : outputs) {
            JsonObject output = e.getAsJsonObject();

            ports[output.get("index").getAsInt()] = output;
        }
        
        for (JsonObject output : ports) {
            String name = jstring(output, "name");            
            StreamSchema schema = Type.Factory.getTupleType(jstring(output, "type")).getTupleSchema();            
            OutputPortDeclaration port = opDecl.addOutput(name, schema);
            
            assert !outputPorts.containsKey(name);
            outputPorts.put(name, port);
        }  
    }
    private void declareInputs(OperatorInvocation<? extends Operator> opDecl, JsonArray inputs) {
        if (jisEmpty(inputs))
            return;
        
        // Ensure we deal with them in port order.
        JsonObject[] ports = new JsonObject[inputs.size()];
        for (JsonElement e : inputs) {
            JsonObject input = e.getAsJsonObject();

            ports[input.get("index").getAsInt()] = input;
        }
        
        for (JsonObject input : ports) {
            String name = jstring(input, "name");            
            StreamSchema schema = Type.Factory.getTupleType(jstring(input, "type")).getTupleSchema();            
            InputPortDeclaration port = opDecl.addInput(name, schema);
            
            assert !inputPorts.containsKey(name);
            inputPorts.put(name, port);
        }  
    }
    
    private void declareConnections() throws Exception {
        for (BOperator op : builder.getOps())
            declareOpConnections(op);
    }

    private void declareOpConnections(BOperator op) {
        JsonObject json = JSON4JUtilities.gson(op.complete());
        JsonArray outputs = json.getAsJsonArray("outputs");
        if (jisEmpty(outputs))
            return;
        
        for (JsonElement e : outputs) {
            JsonObject output = e.getAsJsonObject();
            String name = jstring(output, "name");
            JsonArray conns = output.getAsJsonArray("connections");
            if (jisEmpty(conns))
                continue;
            
            OutputPortDeclaration port = requireNonNull(outputPorts.get(name));
            for (JsonElement c : conns) {
                String iname = c.getAsString();
                InputPortDeclaration iport = requireNonNull(inputPorts.get(iname));               
                port.connect(iport);
            }
        }     
    }

    /**
     * From a JSON parameter set the operator declaration parameter.
     */
    private void setOpParameter(OperatorInvocation<? extends Operator> opDecl, String name, JsonObject param)
    throws Exception
    {
        final JsonElement value = param.get("value");
        
        String type;
        
        if (param.has("type"))
            type = jstring(param, "type");
        else {
            type = "UNKNOWN";
            if (value.isJsonArray())
                type = RSTRING.name();
            else if (value.isJsonPrimitive()) {
                JsonPrimitive pv = value.getAsJsonPrimitive();
                if (pv.isBoolean())
                    type = BOOLEAN.name();
                else if (pv.isString())
                    type = RSTRING.name();
            }               
        }

        
        if (RSTRING.name().equals(type) || USTRING.name().equals(type)) {
            if (value.isJsonArray()) {
                JsonArray values = value.getAsJsonArray();
                String[] sv = new String[values.size()];
                for (int i = 0; i < sv.length; i++)
                    sv[i] = values.get(i).getAsString();
                opDecl.setStringParameter(name, sv);               
            } else
                opDecl.setStringParameter(name, value.getAsString());
        } else if (INT8.name().equals(type) || UINT8.name().equals(type))
            opDecl.setByteParameter(name, value.getAsByte());
        else if (INT16.name().equals(type) || UINT16.name().equals(type))
            opDecl.setShortParameter(name, value.getAsShort());
        else if (INT32.name().equals(type) || UINT32.name().equals(type))
            opDecl.setIntParameter(name, value.getAsInt());
        else if (INT64.name().equals(type) || UINT64.name().equals(type))
            opDecl.setLongParameter(name, value.getAsLong());
        else if (FLOAT32.name().equals(type))
            opDecl.setFloatParameter(name, value.getAsFloat());
        else if (FLOAT64.name().equals(type))
            opDecl.setDoubleParameter(name, value.getAsDouble());
        else if (BOOLEAN.name().equals(type))
            opDecl.setBooleanParameter(name, value.getAsBoolean());
        else if (DECIMAL128.name().equals(type))
            opDecl.setBooleanParameter(name, value.getAsBoolean());
        else if (TYPE_ATTRIBUTE.equals(type))
            opDecl.setAttributeParameter(name, value.getAsString());
        else if (JParamTypes.TYPE_ENUM.equals(type)) {
            final String enumClassName = param.get("enumclass").getAsString();
            final String enumName = value.getAsString();
            final Class<?> enumClass = Class.forName(enumClassName);
            if (enumClass.isEnum()) {
                for (Object eo : enumClass.getEnumConstants()) {
                    Enum<?> e = (Enum<?>) eo;
                    if (e.name().equals(enumName))
                        opDecl.setCustomLiteralParameter(name, e);
                }
            }          
            throw new IllegalArgumentException("Type for parameter " + name + " is not supported:" +  type);
        } else
            throw new IllegalArgumentException("Type for parameter " + name + " is not supported:" +  type);
    }
    
    private IllegalStateException notSupported(BOperator op) {
        
        String namespace = (String) builder.json().get(NAMESPACE);
        String name = (String) builder.json().get(NAME);
        
        return new IllegalStateException(
                "Topology '"+namespace+"."+name+"'"
                + " does not support "+StreamsContext.Type.EMBEDDED+" mode:"
                + " the topology contains non-Java operator:" + op.json().get(KIND));
    }
}
