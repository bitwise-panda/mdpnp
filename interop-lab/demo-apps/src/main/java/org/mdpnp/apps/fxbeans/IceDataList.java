package org.mdpnp.apps.fxbeans;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javafx.application.Platform;
import javafx.collections.ModifiableObservableListBase;
import javafx.collections.ObservableList;

import org.mdpnp.rtiapi.data.EventLoop;
import org.mdpnp.rtiapi.data.LogEntityStatus;
import org.mdpnp.rtiapi.data.TopicUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.infrastructure.Condition;
import com.rti.dds.infrastructure.Copyable;
import com.rti.dds.infrastructure.InstanceHandle_t;
import com.rti.dds.infrastructure.RETCODE_NO_DATA;
import com.rti.dds.infrastructure.ResourceLimitsQosPolicy;
import com.rti.dds.infrastructure.StatusKind;
import com.rti.dds.infrastructure.StringSeq;
import com.rti.dds.subscription.DataReader;
import com.rti.dds.subscription.InstanceStateKind;
import com.rti.dds.subscription.ReadCondition;
import com.rti.dds.subscription.SampleInfo;
import com.rti.dds.subscription.SampleInfoSeq;
import com.rti.dds.subscription.SampleStateKind;
import com.rti.dds.subscription.Subscriber;
import com.rti.dds.subscription.SubscriberQos;
import com.rti.dds.subscription.ViewStateKind;
import com.rti.dds.topic.ContentFilteredTopic;
import com.rti.dds.topic.Topic;
import com.rti.dds.topic.TypeSupport;
import com.rti.dds.util.Sequence;

public class IceDataList<D extends Copyable, R extends DataReader, F extends Updatable<D>> extends ModifiableObservableListBase<F> implements ObservableList<F> {
        private static final Logger log = LoggerFactory.getLogger(IceDataList.class);
        
        private final List<F> data = new ArrayList<>();
        
        private final String topicName;
        
        protected final Class<? extends F> fxClass;
        protected final Class<D> dataClass;
        protected final Class<R> readerClass;
        protected final Class<? extends TypeSupport> typeSupportClass;
        protected final Class<? extends Sequence> sequenceClass;
        protected final Sequence dataSequence;
        protected final SampleInfoSeq sampleInfoSequence = new SampleInfoSeq();
        
        protected final Method getKeyValue, returnLoan, readWCondition, readInstance;
        
        private final LogEntityStatus logEntityStatus;

        public IceDataList(final String topicName, final Class<D> dataClass, final Class<R> readerClass, final Class<? extends TypeSupport> typeSupportClass, final Class<? extends Sequence> sequenceClass, final Class<F> fxClass) {
            this.topicName = topicName;
            this.dataClass = dataClass;
            this.readerClass = readerClass;
            this.typeSupportClass = typeSupportClass;
            this.sequenceClass = sequenceClass;
            this.fxClass = fxClass;
            
            this.logEntityStatus = new LogEntityStatus(log, topicName);
            try {
                this.dataSequence = sequenceClass.newInstance();
                getKeyValue = readerClass.getMethod("get_key_value", dataClass, InstanceHandle_t.class);
                returnLoan = readerClass.getMethod("return_loan", sequenceClass, SampleInfoSeq.class);
                readWCondition = readerClass.getMethod("read_w_condition", sequenceClass, SampleInfoSeq.class, int.class, ReadCondition.class);
                readInstance = readerClass.getMethod("read_instance", sequenceClass, SampleInfoSeq.class, int.class, InstanceHandle_t.class, int.class, int.class, int.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        
        private Subscriber subscriber;
        private EventLoop eventLoop;
        
        protected R reader;
        private ReadCondition condition;
        
        private Topic readerTopic;
        private ContentFilteredTopic filteredTopic;
        
        
        
        private final EventLoop.ConditionHandler handler = new EventLoop.ConditionHandler() {
            @Override
            public void conditionChanged(Condition condition) {
                    try {
                        readWCondition.invoke(reader, dataSequence, sampleInfoSequence, ResourceLimitsQosPolicy.LENGTH_UNLIMITED, (ReadCondition) condition);
                        
                        final int sz = sampleInfoSequence.size();
                        
                        for (int i = 0; i < sz; i++) {
                            SampleInfo sampleInfo = (SampleInfo) sampleInfoSequence.get(i);
                            D d = (D) dataSequence.get(i);
                            if (0 != (sampleInfo.instance_state & InstanceStateKind.NOT_ALIVE_INSTANCE_STATE)) {
                                InstanceHandle_t removeHandle = new InstanceHandle_t(sampleInfo.instance_handle);
                                Platform.runLater( () -> {
                                    Iterator<F> itr = iterator();
                                    while(itr.hasNext()) {
                                        if(itr.next().getHandle().equals(removeHandle)) {
                                            itr.remove();
                                        }
                                    }
                                });
                            } else {
                                final InstanceHandle_t addUpdateHandle = new InstanceHandle_t(sampleInfo.instance_handle);
                                final SampleInfo si = new SampleInfo();
                                si.copy_from(sampleInfo);
                                final D sample = dataClass.newInstance();
                                sample.copy_from(d);
                                
                                Platform.runLater( () -> {
                                    Iterator<F> itr = iterator();
                                    while(itr.hasNext()) {
                                        Updatable<D> x = itr.next();
                                        if(x.getHandle().equals(addUpdateHandle)) {
                                            x.update(d, si);
                                            return;
                                        }
                                    }
                                    try {
                                        F x = fxClass.newInstance();
                                        x.update(d, si);
                                        add(x);
                                    } catch (Exception e) {
                                        log.error("Unable to create a new instance of fx object", e);
                                    }
                                });
                            }
                        }
                    } catch (InvocationTargetException ite) {
                        if(!(ite.getCause() instanceof RETCODE_NO_DATA)) {
                            log.error("reading", ite);
                        }
                    } catch (Exception e) {
                        log.error("reading ", e);
                    } finally {
                        try {
                            returnLoan.invoke(reader, dataSequence, sampleInfoSequence);
                        } catch (Exception e) {
                            log.error("return_loan", e);
                        }
                    }
                }
        };

                
        
        public void start(final Subscriber subscriber, final EventLoop eventLoop, final String expression, final StringSeq params, final String qosLibrary, final String qosProfile) {
            this.subscriber = subscriber;
            this.eventLoop = eventLoop;

            if(null == this.readerTopic) {
                this.readerTopic = TopicUtil.findOrCreateTopic(subscriber.get_participant(), topicName, typeSupportClass);
                if(null != expression) {
                    log.info(getClass()+" Filtered Expression:"+expression+" Filter values:"+params);
                    // TODO time since epoch is a klugey way to get some likelihood of uniqueness
                    filteredTopic = subscriber.get_participant().create_contentfilteredtopic("Filtered"+topicName+System.currentTimeMillis(), readerTopic, expression, params);
                    if(null == filteredTopic) {
                        log.debug("Unable to create filtered topic " + getClass());
                    }
                }
            }
            SubscriberQos sQos = new SubscriberQos();
            subscriber.get_qos(sQos);
            sQos.entity_factory.autoenable_created_entities = false;
            subscriber.set_qos(sQos);

            if(null == qosProfile || null == qosLibrary) {
                reader = (R) subscriber.create_datareader(null==filteredTopic?readerTopic:filteredTopic, Subscriber.DATAREADER_QOS_DEFAULT, 
                        null, StatusKind.STATUS_MASK_NONE);
            } else {
                reader = (R) subscriber.create_datareader_with_profile(null==filteredTopic?readerTopic:filteredTopic, qosLibrary,
                        qosProfile, null, StatusKind.STATUS_MASK_NONE);
            }
            reader.set_listener(logEntityStatus, StatusKind.STATUS_MASK_ALL ^ StatusKind.DATA_AVAILABLE_STATUS);
            sQos.entity_factory.autoenable_created_entities = true;
            subscriber.set_qos(sQos);
        
            condition = reader.create_readcondition(SampleStateKind.NOT_READ_SAMPLE_STATE, ViewStateKind.ANY_VIEW_STATE,
                    InstanceStateKind.ANY_INSTANCE_STATE);
    
            eventLoop.addHandler(condition, handler);
            
            reader.enable();
        }
        
        public void stop() {
            if(null != eventLoop && condition != null) {
                eventLoop.removeHandler(condition);
                eventLoop = null;
            }
            if(condition != null) {
                reader.delete_readcondition(condition);
                condition = null;
            }
            if(subscriber != null) {
                if(reader != null) {
                    subscriber.delete_datareader(reader);
                    reader = null;
                }
                if(null != filteredTopic) {
                    subscriber.get_participant().delete_contentfilteredtopic(filteredTopic);
                    filteredTopic = null;
                }
                if(null != readerTopic) {
                    subscriber.get_participant().delete_topic(readerTopic);
                    readerTopic = null;
                }
                subscriber = null;
            }
            Platform.runLater( () -> clear() );
        }

        @Override
        public F get(int index) {
            return data.get(index);
        }

        @Override
        public int size() {
            return data.size();
        }

        @Override
        protected void doAdd(int index, F element) {
            data.add(index, element);
        }

        @Override
        protected F doSet(int index, F element) {
            return data.set(index, element);
        }

        @Override
        protected F doRemove(int index) {
            return data.remove(index);
        }

        

//        @Override
//        public void instanceSample(ReaderInstanceModel<D, R> model, R reader, D data, SampleInfo sampleInfo) {
//            final ice.Alert d = new ice.Alert(data);
//            SampleInfo s = new SampleInfo();
//            s.copy_from(sampleInfo);
//            Platform.runLater(new Runnable() {
//                public void run() {
//                    MyAlert n = alertsByKey.get(MyAlert.key(d));
//                    if(n != null) {
//                        n.update(d, s);
//                    }
//                }
//            });
//        }
//        
//        @Override
//        public void instanceNotAlive(ReaderInstanceModel<Alert, AlertDataReader> model, AlertDataReader reader, Alert keyHolder, SampleInfo sampleInfo) {
//            final ice.Alert key = new ice.Alert(keyHolder);
//            Platform.runLater(new Runnable() {
//                public void run() {
//                    MyAlert n = alertsByKey.remove(MyAlert.key(key));
//                    if(n != null) {
//                        items.remove(n);
//                    }
//                }
//            });
//        }
//        
//        @Override
//        public void instanceAlive(ReaderInstanceModel<Alert, AlertDataReader> model, AlertDataReader reader, Alert data, SampleInfo sampleInfo) {
//            final MyAlert n = new MyAlert(data, sampleInfo);
//            Platform.runLater(new Runnable() {
//                public void run() {
//                    alertsByKey.put(n.key(), n);
//                    items.add(n);
//                }
//            });
//        }

    

}