package android.net;

/*
 */

import java.io.IOException;
import java.io.FileDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * non-standard class for creating inbound UNIX-domain socket on the Android
 * platform.
 * Actually this is not needed since, the socket is given to us by init
 * 
 */
public class UnixServerSocket {
	LocalServerSocket socket;
	private boolean preferMethodB = false;

	public UnixServerSocket(LocalSocketAddress localAddress) throws IOException {
		// I want to accomplish this:
		//		this.localAddress = localAddress;
		//		impl = new LocalSocketImpl();
		//		impl.create(true);
		//		impl.bind(localAddress);
		//		impl.listen(LISTEN_BACKLOG);

		try {
			Class<?> serverClass = getClass().getClassLoader().loadClass("android.net.LocalServerSocket");
			Field f;
			
			Class<?> implClass = getClass().getClassLoader().loadClass("android.net.LocalSocketImpl");
			Constructor<?> c = implClass.getDeclaredConstructor();
			c.setAccessible(true);
			Object impl = c.newInstance();

			Method m = implClass.getMethod("create", boolean.class);
			m.setAccessible(true);
			m.invoke(impl, true);
			
			m = implClass.getMethod("bind", LocalSocketAddress.class);
			m.setAccessible(true);
			m.invoke(impl, localAddress);

			if (preferMethodB ) {
				m = implClass.getDeclaredMethod("listen", int.class);
				m.setAccessible(true);
				m.invoke(impl, 50);

				socket = new LocalServerSocket ("x");
				socket.close();
				f = serverClass.getDeclaredField("localAddress");
				f.setAccessible(true);
				f.set(socket, localAddress);

				// set final field ?
				f = serverClass.getDeclaredField("impl");
				f.setAccessible(true);
				f.set(socket, impl);
			} else {
			
				// Steal fd from LocalSocketImpl
				f = implClass.getDeclaredField("fd");
				f.setAccessible(true);
				Object fd = f.get(impl);
				f.set(impl, null);

				m = implClass.getDeclaredMethod("close");
				m.setAccessible(true);
				m.invoke(impl);

				socket = new LocalServerSocket ((FileDescriptor)fd);
			}
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			if (e.getCause() != null && e.getCause() instanceof IOException) {
				throw (IOException)e.getCause();
			}
			throw new IOException("Error creating socket", e);
		}
	}

	public UnixServerSocket(int fdInt) throws IOException {
		FileDescriptor fd = new FileDescriptor();
		Field f;
		try {
			f = fd.getClass().getDeclaredField("descriptor");
			f.setAccessible(true);
			f.set(fd, fdInt);
		} catch (Exception e) {
			if (e.getCause() != null && e.getCause() instanceof IOException) {
				throw (IOException)e.getCause();
			}
			throw new IOException("Error creating socket", e);
		}
		socket = new LocalServerSocket (fd);
	}

	public LocalSocket accept() throws IOException {
		return socket.accept();
	}

	public void close() throws IOException {
		socket.close();		
	}
}
