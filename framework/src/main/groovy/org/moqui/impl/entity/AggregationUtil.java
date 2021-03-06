/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.entity;

import groovy.lang.MissingPropertyException;
import groovy.lang.Script;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.moqui.BaseException;
import org.moqui.entity.EntityValue;
import org.moqui.impl.StupidJavaUtilities;
import org.moqui.impl.actions.XmlAction;
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.util.ContextStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.*;

public class AggregationUtil {
    protected final static Logger logger = LoggerFactory.getLogger(AggregationUtil.class);
    protected final static boolean isTraceEnabled = logger.isTraceEnabled();

    public enum AggregateFunction { MIN, MAX, SUM, AVG, COUNT }
    private static final BigDecimal BIG_DECIMAL_TWO = new BigDecimal(2);

    public static class AggregateField {
        public final String fieldName;
        public final AggregateFunction function;
        public final boolean groupBy, subList, showTotal;
        public final Class fromExpr;
        public AggregateField(String fn, AggregateFunction func, boolean gb, boolean sl, boolean st, Class from) {
            fieldName = fn; function = func; groupBy = gb; subList = sl; showTotal = st; fromExpr = from;
        }
    }

    private String listName, listEntryName;
    private AggregateField[] aggregateFields;
    private boolean hasFromExpr = false;
    private String[] groupFields;
    private XmlAction rowActions;

    public AggregationUtil(String listName, String listEntryName, AggregateField[] aggregateFields, String[] groupFields, XmlAction rowActions) {
        this.listName = listName;
        this.listEntryName = listEntryName;
        if (this.listEntryName != null && this.listEntryName.isEmpty()) this.listEntryName = null;
        this.aggregateFields = aggregateFields;
        this.groupFields = groupFields;
        this.rowActions = rowActions;
        for (int i = 0; i < aggregateFields.length; i++) {
            AggregateField aggField = aggregateFields[i];
            if (aggField.fromExpr != null) { hasFromExpr = true; break; }
        }
    }


    @SuppressWarnings("unchecked")
    public ArrayList<Map<String, Object>> aggregateList(Object listObj, boolean makeSubList, ExecutionContextImpl eci) {
        if (groupFields == null || groupFields.length == 0) makeSubList = false;
        ArrayList<Map<String, Object>> resultList = new ArrayList<>();
        if (listObj == null) return resultList;

        long startTime = System.currentTimeMillis();
        Map<Map<String, Object>, Map<String, Object>> groupRows = new HashMap<>();
        int originalCount = 0;
        if (listObj instanceof List) {
            List listList = (List) listObj;
            int listSize = listList.size();
            if (listObj instanceof RandomAccess) {
                for (int i = 0; i < listSize; i++) {
                    Object curObject = listList.get(i);
                    processAggregateOriginal(curObject, resultList, groupRows, i, (i < (listSize - 1)), makeSubList, eci);
                    originalCount++;
                }
            } else {
                int i = 0;
                for (Object curObject : listList) {
                    processAggregateOriginal(curObject, resultList, groupRows, i, (i < (listSize - 1)), makeSubList, eci);
                    i++;
                    originalCount++;
                }
            }
        } else if (listObj instanceof Iterator) {
            Iterator listIter = (Iterator) listObj;
            int i = 0;
            while (listIter.hasNext()) {
                Object curObject = listIter.next();
                processAggregateOriginal(curObject, resultList, groupRows, i, listIter.hasNext(), makeSubList, eci);
                i++;
                originalCount++;
            }
        } else if (listObj.getClass().isArray()) {
            Object[] listArray = (Object[]) listObj;
            int listSize = listArray.length;
            for (int i = 0; i < listSize; i++) {
                Object curObject = listArray[i];
                processAggregateOriginal(curObject, resultList, groupRows, i, (i < (listSize - 1)), makeSubList, eci);
                originalCount++;
            }
        } else {
            throw new BaseException("form-list list " + listName + " is a type we don't know how to iterate: " + listObj.getClass().getName());
        }

        if (logger.isInfoEnabled()) logger.info("Processed list " + listName + ", from " + originalCount + " items to " + resultList.size() + " items, in " + (System.currentTimeMillis() - startTime) + "ms");
        // for (Map<String, Object> result : resultList) logger.warn("Aggregate Result: " + result.toString());

        return resultList;
    }

    @SuppressWarnings("unchecked")
    private void processAggregateOriginal(Object curObject, ArrayList<Map<String, Object>> resultList,
                                          Map<Map<String, Object>, Map<String, Object>> groupRows, int index, boolean hasNext,
                                          boolean makeSubList, ExecutionContextImpl eci) {

        Map curMap = null;
        if (curObject instanceof EntityValue) {
            curMap = ((EntityValue) curObject).getMap();
        } else if (curObject instanceof Map) {
            curMap = (Map) curObject;
        }
        boolean curIsMap = curMap != null;

        ContextStack context = eci.contextStack;
        Map<String, Object> contextTopMap;
        if (curMap != null) { contextTopMap = new HashMap<>(curMap); } else { contextTopMap = new HashMap<>(); }
        context.push(contextTopMap);

        if (listEntryName != null) {
            context.put(listEntryName, curObject);
            context.put(listEntryName + "_index", index);
            context.put(listEntryName + "_has_next", hasNext);
        } else {
            context.put(listName + "_index", index);
            context.put(listName + "_has_next", hasNext);
            context.put(listName + "_entry", curObject);
        }

        // if there are row actions run them
        if (rowActions != null || hasFromExpr) {
            if (rowActions != null) rowActions.run(eci);

            // if any fields have a fromExpr get the value from that
            for (int i = 0; i < aggregateFields.length; i++) {
                AggregateField aggField = aggregateFields[i];
                if (aggField.fromExpr != null) {
                    Script script = InvokerHelper.createScript(aggField.fromExpr, eci.contextBindingInternal);
                    Object newValue = script.run();
                    context.put(aggField.fieldName, newValue);
                }
            }
        }

        Map<String, Object> resultMap = null;
        Map<String, Object> groupByMap = null;
        if (makeSubList) {
            groupByMap = new HashMap<>();
            for (int i = 0; i < groupFields.length; i++) {
                String groupBy = groupFields[i];
                groupByMap.put(groupBy, getField(groupBy, context, curObject, curIsMap));
            }
            resultMap = groupRows.get(groupByMap);
        }

        Map<String, Object> subListMap = null;
        if (resultMap == null) {
            resultMap = contextTopMap;
            for (int i = 0; i < aggregateFields.length; i++) {
                AggregateField aggField = aggregateFields[i];
                String fieldName = aggField.fieldName;
                Object fieldValue = getField(fieldName, context, curObject, curIsMap);
                // don't want to put null values, a waste of time/space; if count aggregate continue so it isn't counted
                if (fieldValue == null) continue;

                if (makeSubList && aggField.subList) {
                    // NOTE: may have an issue here not using contextTopMap as starting point for sub-list entry, ie row-actions values lost if not referenced in a field name/from
                    // NOTE2: if we start with contextTopMap should clone and perhaps remove aggregateFields that are not sub-list
                    if (subListMap == null) subListMap = new HashMap<>();
                    subListMap.put(fieldName, fieldValue);
                } else if (aggField.function == AggregateFunction.COUNT) {
                    resultMap.put(fieldName, 1);
                } else {
                    resultMap.put(fieldName, fieldValue);
                }
                // TODO: handle showTotal
            }
            if (subListMap != null) {
                ArrayList<Map<String, Object>> subList = new ArrayList<>();
                subList.add(subListMap);
                resultMap.put("aggregateSubList", subList);
            }
            resultList.add(resultMap);
            if (makeSubList) groupRows.put(groupByMap, resultMap);
        } else {
            // NOTE: if makeSubList == false this will never run
            for (int i = 0; i < aggregateFields.length; i++) {
                AggregateField aggField = aggregateFields[i];
                String fieldName = aggField.fieldName;

                if (aggField.subList) {
                    // NOTE: may have an issue here not using contextTopMap as starting point for sub-list entry, ie row-actions values lost if not referenced in a field name/from
                    if (subListMap == null) subListMap = new HashMap<>();
                    subListMap.put(fieldName, getField(fieldName, context, curObject, curIsMap));
                } else if (aggField.function != null) {
                    switch (aggField.function) {
                        case MIN:
                        case MAX:
                            Comparable existingComp = (Comparable) resultMap.get(fieldName);
                            Comparable newComp = (Comparable) getField(fieldName, context, curObject, curIsMap);
                            if (existingComp == null) {
                                if (newComp != null) resultMap.put(fieldName, newComp);
                            } else {
                                int compResult = existingComp.compareTo(newComp);
                                if ((aggField.function == AggregateFunction.MIN && compResult < 0) ||
                                        (aggField.function == AggregateFunction.MAX && compResult > 0))
                                    resultMap.put(fieldName, newComp);
                            }
                            break;
                        case SUM:
                            Number sumNum = StupidJavaUtilities.addNumbers((Number) resultMap.get(fieldName), (Number) getField(fieldName, context, curObject, curIsMap));
                            if (sumNum != null) resultMap.put(fieldName, sumNum);
                            break;
                        case AVG:
                            Number newNum = (Number) getField(fieldName, context, curObject, curIsMap);
                            if (newNum != null) {
                                Number existingNum = (Number) resultMap.get(fieldName);
                                if (existingNum == null) {
                                    resultMap.put(fieldName, newNum);
                                } else {
                                    String fieldCountName = fieldName.concat("Count");
                                    BigDecimal count = (BigDecimal) resultMap.get(fieldCountName);
                                    BigDecimal bd1 = (existingNum instanceof BigDecimal) ? (BigDecimal) existingNum : new BigDecimal(existingNum.toString());
                                    BigDecimal bd2 = (newNum instanceof BigDecimal) ? (BigDecimal) newNum : new BigDecimal(newNum.toString());
                                    if (count == null) {
                                        resultMap.put(fieldName, bd1.add(bd2).divide(BIG_DECIMAL_TWO, BigDecimal.ROUND_HALF_EVEN));
                                        resultMap.put(fieldCountName, BIG_DECIMAL_TWO);
                                    } else {
                                        BigDecimal avgTotal = bd1.multiply(count).add(bd2);
                                        BigDecimal countPlusOne = count.add(BigDecimal.ONE);
                                        resultMap.put(fieldName, avgTotal.divide(countPlusOne, BigDecimal.ROUND_HALF_EVEN));
                                        resultMap.put(fieldCountName, countPlusOne);
                                    }
                                }
                            }
                            break;
                        case COUNT:
                            Integer existingCount = (Integer) resultMap.get(fieldName);
                            resultMap.put(fieldName, existingCount + 1);
                            break;
                    }
                }
                // TODO: handle showTotal
            }
            if (subListMap != null) {
                ArrayList<Map<String, Object>> subList = (ArrayList<Map<String, Object>>) resultMap.get("aggregateSubList");
                subList.add(subListMap);
            }
        }

        // all done, pop the row context to clean up
        context.pop();
    }

    private Object getField(String fieldName, ContextStack context, Object curObject, boolean curIsMap) {
        Object value = context.getByString(fieldName);
        if (StupidJavaUtilities.isEmpty(value) && !curIsMap) {
            // try Groovy getAt for property access
            try {
                value = DefaultGroovyMethods.getAt(curObject, fieldName);
            } catch (MissingPropertyException e) {
                // ignore exception, we know this may not be a real property of the object
                if (isTraceEnabled) logger.trace("Field " + fieldName + " is not a property of list-entry " + listEntryName + " in list " + listName + ": " + e.toString());
            }
        }
        return value;
    }
}
