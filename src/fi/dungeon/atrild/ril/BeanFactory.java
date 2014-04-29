package fi.dungeon.atrild.ril;

public interface BeanFactory extends Bean {
	
	public void initialize();
	public void destroy();	
	public void registerBean(Object o);
	public Object getBean(String key);
	public <T> T getBean(Class<T> type);
	public void setBeanProperty(String name, String property, String value);
	public String getBeanProperty(String name, String property);
}
