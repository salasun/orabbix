# orabbix
orabbix for zabbix4.0 and oracle rac

orabbix don't support zabbix4.0. because 
https://www.zabbix.com/documentation/4.0/manual/appendix/protocols/header_datalen

you should edit Sender.java and add this in function send():
```Java
byte[] data = message.toString().getBytes();
byte[] header = new byte[] {
        'Z', 'B', 'X', 'D', '\1',
        (byte)(data.length & 0xFF),
        (byte)((data.length >> 8) & 0xFF),
        (byte)((data.length >> 16) & 0xFF),
        (byte)((data.length >> 24) & 0xFF),
        '\0', '\0', '\0', '\0'};
byte[] packet = new byte[header.length + data.length];
System.arraycopy(header, 0, packet, 0, header.length);
System.arraycopy(data, 0, packet, header.length, data.length);
```
and change OutputStreamWriter to DataOutputStream for send byte[]:
```Java
DataOutputStream out = null;
out = new DataOutputStream(zabbix.getOutputStream());
out.write(packet);
```
then it works for zabbix4.0.
