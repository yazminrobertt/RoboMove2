package android.service.reeman;

interface IReemanService {
    int send_to_ttys4(in byte[] data);
    int send_to_ttys3(in byte[] data);
}