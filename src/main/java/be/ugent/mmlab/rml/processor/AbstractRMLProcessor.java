package be.ugent.mmlab.rml.processor;

import be.ugent.mmlab.rml.core.BindRMLPerformer;
import be.ugent.mmlab.rml.core.ConditionalJoinRMLPerformer;
import be.ugent.mmlab.rml.core.JoinRMLPerformer;
import be.ugent.mmlab.rml.core.RMLEngine;
import be.ugent.mmlab.rml.core.RMLPerformer;
import be.ugent.mmlab.rml.core.SimpleReferencePerformer;
import be.ugent.mmlab.rml.model.GraphMap;
import be.ugent.mmlab.rml.model.JoinCondition;
import be.ugent.mmlab.rml.model.LogicalSource;
import be.ugent.mmlab.rml.model.ObjectMap;
import be.ugent.mmlab.rml.model.PredicateMap;
import be.ugent.mmlab.rml.model.PredicateObjectMap;
import be.ugent.mmlab.rml.model.ReferencingObjectMap;
import be.ugent.mmlab.rml.model.SubjectMap;
import be.ugent.mmlab.rml.model.TermMap;
import be.ugent.mmlab.rml.model.TermType;
import static be.ugent.mmlab.rml.model.TermType.BLANK_NODE;
import static be.ugent.mmlab.rml.model.TermType.IRI;
import be.ugent.mmlab.rml.model.TriplesMap;
import be.ugent.mmlab.rml.model.condition.BindCondition;
import be.ugent.mmlab.rml.model.reference.ReferenceIdentifierImpl;
import be.ugent.mmlab.rml.processor.concrete.ConcreteRMLProcessorFactory;
import be.ugent.mmlab.rml.processor.condition.ConditionProcessor;
import be.ugent.mmlab.rml.vocabulary.RMLVocabulary.QLTerm;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.antidot.semantic.rdf.model.impl.sesame.SesameDataSet;
import net.antidot.semantic.rdf.rdb2rdf.r2rml.tools.R2RMLToolkit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openrdf.model.Resource;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.impl.BNodeImpl;
import org.openrdf.model.impl.LiteralImpl;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.model.vocabulary.RDF;
import java.util.regex.Pattern;

/**
 * This class contains all generic functionality for executing an iteration and
 * processing the mapping
 *
 * @author mielvandersande, andimou
 */
public abstract class AbstractRMLProcessor implements RMLProcessor {

    /**
     * Gets the globally defined identifier-to-path map
     *
     * @param ls the current LogicalSource
     * @return the location of the file or table
     */
    // Log
    private static Log log = LogFactory.getLog(AbstractRMLProcessor.class);

    /*protected String getIdentifier(LogicalSource ls) {
        return RMLEngine.getFileMap().getProperty(ls.getIdentifier());
    }*/

    /**
     * gets the expression specified in the logical source
     *
     * @param ls
     * @return
     */
    protected String getReference(LogicalSource ls) {
        return ls.getReference();
    }

    /**
     *
     * Process the subject map
     *
     * @param dataset
     * @param subjectMap
     * @param node
     * @return the created subject
     */
    @Override
    public Resource processSubjectMap(SesameDataSet dataset, SubjectMap subjectMap, Object node) {  
        //Get the uri
        List<String> values = processTermMap(subjectMap, node);
        //log.info("Abstract RML Processor Graph Map" + subjectMap.getGraphMaps().toString());
        if (values == null || values.isEmpty()) 
            if(subjectMap.getTermType() != BLANK_NODE)
                return null;
            
        String value = null;
        if(subjectMap.getTermType() != BLANK_NODE){
            //Since it is the subject, more than one value is not allowed. 
            //Only return the first one. Throw exception if not?
            value = values.get(0);

            if ((value == null) || (value.equals(""))) 
                return null;
        }
        
        Resource subject = null;
                
        //TODO: doublicate code from ObjectMap - they should be handled together
        switch (subjectMap.getTermType()) {
            case IRI:
                if (value != null && !value.equals("")) 
                    if (value.startsWith("www.")) {
                        value = "http://" + value;
                    }
                    subject = new URIImpl(value);
                break;
            case BLANK_NODE:
                subject = new BNodeImpl(org.apache.commons.lang.RandomStringUtils.randomAlphanumeric(10));
                break;
            default:
                subject = new URIImpl(value);
        }
        return subject;
    }
    
    @Override
    public void processSubjectTypeMap(SesameDataSet dataset, Resource subject, SubjectMap subjectMap, Object node) {

        //Add the type triples
        Set<org.openrdf.model.URI> classIRIs = subjectMap.getClassIRIs();
        if(subject != null)
            for (org.openrdf.model.URI classIRI : classIRIs) 
                if(subjectMap.getGraphMaps().isEmpty())
                    dataset.add(subject, RDF.TYPE, classIRI);
                else
                    for (GraphMap map : subjectMap.getGraphMaps()) 
                        if (map.getConstantValue() != null) 
                            dataset.add(subject, RDF.TYPE, classIRI, new URIImpl(map.getConstantValue().toString()));
    }

    /**
     * Process any Term Map
     *
     * @param map current term map
     * @param node current node in iteration
     * @return the resulting value
     */

    @Override
    public List<String> processTermMap(TermMap map, Object node) {
        List<String> values = new ArrayList<>(), valueList = new ArrayList<>();
        List<String> validValues = new ArrayList<>();

        switch (map.getTermMapType()) {
            case REFERENCE_VALUED:
                //Get the expression and extract the value
                ReferenceIdentifierImpl identifier = (ReferenceIdentifierImpl) map.getReferenceValue();
                values = extractValueFromNode(node, identifier.toString().trim());
                for (String value : values) {
                    valueList.addAll(ConditionProcessor.processAllConditions(map, value));

                    if (valueList.isEmpty()) {
                        if (map.getSplit() != null
                                || map.getProcess() != null
                                || map.getReplace() != null) {
                            List<String> tempValueList =
                                    ConditionProcessor.postProcessTermMap(map, value, null);
                            if (tempValueList != null) {
                                for (String tempVal : tempValueList) {
                                    valueList.add(tempVal);
                                }
                            }
                        } else {
                            valueList.add(value.trim().replace("\n", " "));
                        }
                    }
                }
                return valueList;

            case CONSTANT_VALUED:
                //Extract the value directly from the mapping
                values.add(map.getConstantValue().stringValue().trim());
                return values;

            case TEMPLATE_VALUED:
                //Resolve the template
                String template = map.getStringTemplate();
                Set<String> tokens = R2RMLToolkit.extractColumnNamesFromStringTemplate(template);
                for (String expression : tokens) {
                    List<String> replacements = extractValueFromNode(node, expression);
                    if (replacements != null) {
                        for (int i = 0; i < replacements.size(); i++) {
                            if (values.size() < (i + 1)) {
                                values.add(template);
                            }
                            String replacement = replacements.get(i);
                            if (replacement != null || !replacement.equals("")) {
                                //process equal conditions
                                List<String> list;

                                //process split, process and replace conditions
                                if (map.getSplit() != null || map.getProcess() != null || map.getReplace() != null) {
                                    list = ConditionProcessor.postProcessTermMap(map, replacement, null);
                                    if (list != null) {
                                        for (String val : list) {
                                            String temp = processTemplate(map, expression, template, val);
                                            if (R2RMLToolkit.extractColumnNamesFromStringTemplate(temp).isEmpty()) {
                                                validValues.add(temp);
                                            }
                                        }
                                    }
                                } else {
                                    list = ConditionProcessor.processAllConditions(map, replacement);

                                    if (!list.isEmpty()) {
                                        for (String value : list) {
                                            values.add(template);
                                            String temp = processTemplate(map, expression, template, value);

                                            if (R2RMLToolkit.extractColumnNamesFromStringTemplate(temp).isEmpty()) {
                                                validValues.add(temp);
                                            } else {
                                                template = temp;
                                            }
                                        }
                                    } else if (!replacement.isEmpty()) {
                                        String temp = processTemplate(map, expression, template, replacement);
                                        template = temp;
                                        if (R2RMLToolkit.extractColumnNamesFromStringTemplate(temp).isEmpty()) {
                                            validValues.add(temp);
                                        }
                                    }

                                }
                            } else {
                                log.debug(Thread.currentThread().getStackTrace()[1].getMethodName() + ": "
                                        + "No suitable replacement for template " + template + ".");
                                return null;
                            }
                        }
                    } else {
                        log.debug(Thread.currentThread().getStackTrace()[1].getMethodName() + ": "
                                + "No replacements found for template " + template + ".");
                        return null;
                    }
                }

                //Check if there are any placeholders left in the templates and remove uris that are not
                for (String uri : values) {
                    if (R2RMLToolkit.extractColumnNamesFromStringTemplate(uri).isEmpty()) {
                        validValues.add(uri);
                    }
                }
                return validValues;

            default:
                return values;
        }

        //return value;
    }
    
    @Override
    public List<String> processTemplate(
            TermMap map, List<String> replacements, String expression) {
        List<String> values = new ArrayList<>(), validValues = new ArrayList<>();
        String template = map.getStringTemplate();
        
        for (int i = 0; i < replacements.size(); i++) {
            if (values.size() < (i + 1)) {
                values.add(template);
            }
            String replacement = replacements.get(i);
            if (replacement != null || !replacement.equals("")) {
                List<String> list;

                //process split, process and replace conditions
                if (map.getSplit() != null || map.getProcess() != null || map.getReplace() != null) {
                    list = ConditionProcessor.postProcessTermMap(map, replacement, null);
                    if (list != null) {
                        for (String val : list) {
                            String temp = processTemplate(map, expression, template, val);
                            if (R2RMLToolkit.extractColumnNamesFromStringTemplate(temp).isEmpty()) {
                                validValues.add(temp);
                            }
                        }
                    }
                } else {
                    list = ConditionProcessor.processAllConditions(map, replacement);

                    if (!list.isEmpty()) {
                        for (String value : list) {
                            values.add(template);
                            String temp = processTemplate(map, expression, template, value);

                            if (R2RMLToolkit.extractColumnNamesFromStringTemplate(temp).isEmpty()) {
                                validValues.add(temp);
                            } else {
                                template = temp;
                            }
                        }
                    } else if (!replacement.isEmpty()) {
                        String temp = processTemplate(map, expression, template, replacement);
                        template = temp;
                        if (R2RMLToolkit.extractColumnNamesFromStringTemplate(temp).isEmpty()) {
                            validValues.add(temp);
                        }
                    }

                }
            } else {
                log.debug(Thread.currentThread().getStackTrace()[1].getMethodName() + ": "
                        + "No suitable replacement for template " + template + ".");
                return null;
            }
        }
        
        return validValues;
    }
    
    public String processTemplate(TermMap map, String expression, String template, String replacement) {
        if (expression.contains("[")) {
            expression = expression.replaceAll("\\[", "").replaceAll("\\]", "");
            template = template.replaceAll("\\[", "").replaceAll("\\]", "");
        }
        //JSONPath expression cause problems when replacing, remove the $ first
        if ((map.getOwnTriplesMap().getLogicalSource().getReferenceFormulation() == QLTerm.JSONPATH_CLASS)
                && expression.contains("$")) {
            expression = expression.replaceAll("\\$", "");
            template = template.replaceAll("\\$", "");
        }
        try {
            if (map.getTermType().toString().equals(TermType.IRI.toString())) {
                //TODO: replace the following with URIbuilder
                template = template.replaceAll("\\{" + Pattern.quote(expression) + "\\}",
                        URLEncoder.encode(replacement, "UTF-8")
                        .replaceAll("\\+", "%20")
                        .replaceAll("\\%21", "!")
                        .replaceAll("\\%27", "'")
                        .replaceAll("\\%28", "(")
                        .replaceAll("\\%29", ")")
                        .replaceAll("\\%7E", "~"));
            } else {
                template = template.replaceAll("\\{" + expression + "\\}", replacement);
            }
        } catch (UnsupportedEncodingException ex) {
            Logger.getLogger(AbstractRMLProcessor.class.getName()).log(Level.SEVERE, null, ex);
        }
        return template.toString();
    }
    
    @Override
    public String processTemplate(String expression, String template, String termType,
            QLTerm referenceFormulation, String replacement) {
        if (expression.contains("[")) {
            expression = expression.replaceAll("\\[", "").replaceAll("\\]", "");
            template = template.replaceAll("\\[", "").replaceAll("\\]", "");
        }
        //JSONPath expression cause problems when replacing, remove the $ first
        if ((referenceFormulation == QLTerm.JSONPATH_CLASS)
                && expression.contains("$")) {
            expression = expression.replaceAll("\\$", "");
            template = template.replaceAll("\\$", "");
        }
        try {
            if (termType.equals(TermType.IRI.toString())) {
                //TODO: replace the following with URIbuilder
                template = template.replaceAll("\\{" + Pattern.quote(expression) + "\\}",
                        URLEncoder.encode(replacement, "UTF-8")
                        .replaceAll("\\+", "%20")
                        .replaceAll("\\%21", "!")
                        .replaceAll("\\%27", "'")
                        .replaceAll("\\%28", "(")
                        .replaceAll("\\%29", ")")
                        .replaceAll("\\%7E", "~"));
            } else {
                template = template.replaceAll("\\{" + expression + "\\}", replacement);
            }
        } catch (UnsupportedEncodingException ex) {
            Logger.getLogger(AbstractRMLProcessor.class.getName()).log(Level.SEVERE, null, ex);
        }
        return template.toString();
    }
    
    /**
     * Process a predicate object map
     *
     * @param dataset
     * @param subject   the subject from the triple
     * @param pom       the predicate object map
     * @param node      the current node
     */
    @Override
    public void processPredicateObjectMap(
            SesameDataSet dataset, Resource subject, PredicateObjectMap pom, Object node, TriplesMap map) {

        Set<PredicateMap> predicateMaps = pom.getPredicateMaps();
        //Go over each predicate map
        for (PredicateMap predicateMap : predicateMaps) {
            //Get the predicate
            List<URI> predicates = processPredicateMap(predicateMap, node);
            
            URI predicate = predicates.get(0);
            
            //Process the joins first
            processPredicateObjectMap_RefObjMap(dataset, subject, predicate, pom, node, map);
            
            //process the objectmaps
            processPredicateObjectMap_ObjMap(dataset, subject, predicate, pom, node);
            
        }
    }
    
    private void processPredicateObjectMap_RefObjMap(
            SesameDataSet dataset, Resource subject, URI predicate,
            PredicateObjectMap pom, Object node, TriplesMap map) {
        String template = null;
        Set<ReferencingObjectMap> referencingObjectMaps = pom.getReferencingObjectMaps();
        for (ReferencingObjectMap referencingObjectMap : referencingObjectMaps) {
            Set<JoinCondition> joinConditions = referencingObjectMap.getJoinConditions();
            
            TriplesMap parentTriplesMap = referencingObjectMap.getParentTriplesMap();
            
            Set<BindCondition> bindConditions = referencingObjectMap.getBindConditions();
            if(bindConditions != null & bindConditions.size() > 0 )
                template = processBindConditions(node, parentTriplesMap, bindConditions);
            else 
                template = parentTriplesMap.getLogicalSource().getIdentifier();
            
            //Create the processor based on the parent triples map to perform the join
            RMLProcessorFactory factory = new ConcreteRMLProcessorFactory();
            QLTerm referenceFormulation = parentTriplesMap.getLogicalSource().getReferenceFormulation();
            //String source = parentTriplesMap.getLogicalSource().getIdentifier();

            InputStream input = null;
            try {
                input = RMLEngine.getInputStream(template, parentTriplesMap);
            } catch (MalformedURLException ex) {
                Logger.getLogger(AbstractRMLProcessor.class.getName()).log(Level.SEVERE, null, ex);
            } catch (IOException ex) {
                Logger.getLogger(AbstractRMLProcessor.class.getName()).log(Level.SEVERE, null, ex);
            }
            RMLProcessor processor = factory.create(referenceFormulation);

            RMLPerformer performer = null;
            //different Logical Source and no Conditions
            if(bindConditions != null & bindConditions.size() > 0 ){
                performer = new BindRMLPerformer(processor, subject, predicate);
                processor.execute(dataset, parentTriplesMap, performer, input);
            }
            else if (joinConditions.isEmpty()
                    & !parentTriplesMap.getLogicalSource().getIdentifier().equals(map.getLogicalSource().getIdentifier())) {
                performer = new JoinRMLPerformer(processor, subject, predicate);
                processor.execute(dataset, parentTriplesMap, performer, input);
            } //same Logical Source and no Conditions
            else if (joinConditions.isEmpty()
                    & parentTriplesMap.getLogicalSource().getIdentifier().equals(map.getLogicalSource().getIdentifier())) {
                performer = new SimpleReferencePerformer(processor, subject, predicate);
                if ((parentTriplesMap.getLogicalSource().getReferenceFormulation().toString().equals("CSV"))
                        || (parentTriplesMap.getLogicalSource().getReference().equals(map.getLogicalSource().getReference()))) {
                    performer.perform(node, dataset, parentTriplesMap);
                } else {
                    int end = map.getLogicalSource().getReference().length();
                    //log.info("RML:AbstractRMLProcessor " + parentTriplesMap.getLogicalSource().getReference().toString());
                    String expression = "";
                    switch (parentTriplesMap.getLogicalSource().getReferenceFormulation().toString()) {
                        case "XPath":
                            expression = parentTriplesMap.getLogicalSource().getReference().toString().substring(end);
                            break;
                        case "JSONPath":
                            expression = parentTriplesMap.getLogicalSource().getReference().toString().substring(end + 1);
                            break;
                        case "CSS3":
                            expression = parentTriplesMap.getLogicalSource().getReference().toString().substring(end);
                            break;
                    }
                    processor.execute_node(dataset, expression, parentTriplesMap, performer, node, null);
                }
            } //Conditions
            else {
                //Build a join map where
                //  key: the parent expression
                //  value: the value extracted from the child
                processJoinConditions(node, performer, processor, subject, predicate, 
                        dataset, input, parentTriplesMap, joinConditions);
            }
        }
    }
    
    public void processJoinConditions(Object node, RMLPerformer performer, RMLProcessor processor, 
            Resource subject, URI predicate, SesameDataSet dataset, InputStream input, 
            TriplesMap parentTriplesMap, Set<JoinCondition> joinConditions) {
        HashMap<String, String> joinMap = new HashMap<>();

        for (JoinCondition joinCondition : joinConditions) {
            List<String> childValues = extractValueFromNode(node, joinCondition.getChild());
            //Allow multiple values as child - fits with RML's definition of multiple Object Maps
            for (String childValue : childValues) {               
                joinMap.put(joinCondition.getParent(), childValue);
                if (joinMap.size() == joinConditions.size()) {
                    performer = new ConditionalJoinRMLPerformer(processor, joinMap, subject, predicate);
                    processor.execute(dataset, parentTriplesMap, performer, input);
                }
            }
        }
    }
    
    public String processBindConditions(Object node, TriplesMap parentTriplesMap, 
            Set<BindCondition> bindConditions) {
        String finalTemplate = null;
        
        for (BindCondition bindCondition : bindConditions) {
            List<String> bindReferenceValues = extractValueFromNode(node, bindCondition.getReference());
            
            String template = parentTriplesMap.getLogicalSource().getIdentifier();
            String termType = TermType.IRI.toString();
            QLTerm referenceFormulation = parentTriplesMap.getLogicalSource().getReferenceFormulation();
            finalTemplate = processTemplate(bindCondition.getValue(), template, termType,
                               referenceFormulation, bindReferenceValues.get(0));
        }
        return finalTemplate;
    }
    
    @Override
    public void processPredicateObjectMap_ObjMap(
            SesameDataSet dataset, Resource subject, URI predicate,
            PredicateObjectMap pom, Object node) {
        Set<ObjectMap> objectMaps = pom.getObjectMaps();
        for (ObjectMap objectMap : objectMaps) {
            //Get the one or more objects returned by the object map
            List<Value> objects = processObjectMap(objectMap, node);
            if (objects != null) {
                for (Value object : objects) {
                    if (object.stringValue() != null) {
                        Set<GraphMap> graphs = pom.getGraphMaps();
                        if (graphs.isEmpty() && subject != null) {
                            dataset.add(subject, predicate, object);
                        } else {
                            for (GraphMap graph : graphs) {
                                Resource graphResource = new URIImpl(graph.getConstantValue().toString());
                                dataset.add(subject, predicate, object, graphResource);
                            }
                        }

                    }
                }
            } else {
                log.debug(Thread.currentThread().getStackTrace()[1].getMethodName() + ": "
                        + "No object created. No triple will be generated.");
            }
        }
    }

    /**
     * process a predicate map
     *
     * @param predicateMap
     * @param node
     * @return the uri of the extracted predicate
     */
    @Override
    public List<URI> processPredicateMap(PredicateMap predicateMap, Object node) {
        // Get the value
        
        List<String> values = processTermMap(predicateMap, node);
        List<URI> uris = new ArrayList<>();
        for (String value : values) {
            //TODO: add better control
            if(value.startsWith("www."))
                value = "http://" + value;
            uris.add(new URIImpl(value));
        }
        //return the uri
        return uris;
    }

    /**
     * process an object map
     *
     * @param objectMap
     * @param node
     * @return
     */
    public List<Value> processObjectMap(ObjectMap objectMap, Object node) {
        //A Term map returns one or more values (in case expression matches more)
        List<String> values = processTermMap(objectMap, node);
        List<Value> valueList = new ArrayList<>();
        for (String value : values) {
            valueList = applyTermType(value, valueList, objectMap);
        }
        
        return valueList;
    }
    
    @Override
    public List<Value> applyTermType(String value, List<Value> valueList, TermMap termMap){
        TermType termType = termMap.getTermType();
        String languageTag = termMap.getLanguageTag();
        URI datatype = termMap.getDataType();
        
        switch (termType) {
            case IRI:
                if (value != null && !value.equals("")) {
                    if (value.startsWith("www.")) {
                        value = "http://" + value;
                    }
                    if (valueList == null) {
                        valueList = new ArrayList<Value>();
                    }
                    try {
                        new URIImpl(cleansing(value));
                    } catch (Exception e) {
                        return valueList;
                    }
                    valueList.add(new URIImpl(cleansing(value)));
                } 
                break;
            case BLANK_NODE:
                valueList.add(new BNodeImpl(cleansing(value)));
                break;
            case LITERAL:
                if (languageTag != null && !value.equals("")) {
                    if (valueList == null) {
                        valueList = new ArrayList<Value>();
                    }
                    value = cleansing(value);
                    valueList.add(new LiteralImpl(value, languageTag));
                } else if (value != null && !value.equals("") && datatype != null) {
                    valueList.add(new LiteralImpl(value, datatype));
                } else if (value != null && !value.equals("")) {
                    valueList.add(new LiteralImpl(value.trim()));
                }
        }
        return valueList;
    }
    
    /**
     *
     * @param split
     * @param node
     * @return
     */
    @Override
    public List<String> postProcessLogicalSource(String split, Object node) {
        String[] list;
        List<String> valueList = null;

        if (split != null) {
            list = node.toString().split(split);

            for (String item : list) {
                if (valueList == null) {
                    valueList = new ArrayList<String>();
                }
                valueList.add(cleansing(item));
            }
        }
        return valueList;
    }
}
