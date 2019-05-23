package cn.banny.unidbg;

import cn.banny.auxiliary.Inspector;
import cn.banny.unidbg.arm.Arm32RegisterContext;
import cn.banny.unidbg.arm.HookStatus;
import cn.banny.unidbg.hook.ReplaceCallback;
import cn.banny.unidbg.ios.FishHook;
import cn.banny.unidbg.hook.fishhook.IFishHook;
import cn.banny.unidbg.hook.hookzz.*;
import cn.banny.unidbg.hook.whale.IWhale;
import cn.banny.unidbg.hook.whale.Whale;
import cn.banny.unidbg.ios.DarwinARMEmulator;
import cn.banny.unidbg.ios.DarwinResolver;
import cn.banny.unidbg.ios.MachOLoader;
import cn.banny.unidbg.ios.MachOModule;
import cn.banny.unidbg.memory.MemoryBlock;
import cn.banny.unidbg.pointer.UnicornPointer;
import com.sun.jna.Pointer;
import junit.framework.AssertionFailedError;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import unicorn.ArmConst;
import unicorn.Unicorn;

import java.io.File;
import java.util.Arrays;

public class SubstrateTest extends EmulatorTest {

    @Override
    protected LibraryResolver createLibraryResolver() {
        return new DarwinResolver();
    }

    @Override
    protected Emulator createARMEmulator() {
        return new DarwinARMEmulator("com.substrate.test");
    }

    public void testMS() throws Exception {
        MachOLoader loader = (MachOLoader) emulator.getMemory();
        loader.setCallInitFunction();
        loader.setObjcRuntime(true);
//        emulator.attach().addBreakPoint(null, 0x4097855c);
//        emulator.traceCode();
        Module module = emulator.loadLibrary(new File("src/test/resources/example_binaries/libsubstrate.dylib"));

//        Logger.getLogger("cn.banny.emulator.ios.ARM32SyscallHandler").setLevel(Level.DEBUG);
//        emulator.traceCode();

        IFishHook fishHook = FishHook.getInstance(emulator);
        fishHook.rebindSymbol("memcpy", new ReplaceCallback() {
            @Override
            public HookStatus onCall(Emulator emulator, long originFunction) {
                Unicorn unicorn = emulator.getUnicorn();
                Pointer dest = UnicornPointer.register(emulator, ArmConst.UC_ARM_REG_R0);
                Pointer src = UnicornPointer.register(emulator, ArmConst.UC_ARM_REG_R1);
                int size = ((Number) unicorn.reg_read(ArmConst.UC_ARM_REG_R2)).intValue();
                System.err.println("memcpy dest=" + dest + ", src=" + src + ", size=" + size);
                return HookStatus.RET(unicorn, originFunction);
            }
        });

        IWhale whale = Whale.getInstance(emulator);
        /*whale.WImportHookFunction("_malloc", new ReplaceCallback() {
            @Override
            public HookStatus onCall(Emulator emulator, long originFunction) {
                Unicorn unicorn = emulator.getUnicorn();
                int size = ((Number) unicorn.reg_read(ArmConst.UC_ARM_REG_R0)).intValue();
                System.err.println("IWhale hook _malloc size=" + size);
                return HookStatus.RET(unicorn, originFunction);
            }
        });*/

        Symbol malloc_num_zones = module.findSymbolByName("_malloc_num_zones");
        assertNotNull(malloc_num_zones);
        System.out.println("malloc_num_zones=" + malloc_num_zones.createPointer(emulator).getInt(0));
        Symbol malloc_default_zone = module.findSymbolByName("_malloc_default_zone");
        Symbol malloc_size = module.findSymbolByName("_malloc_size");
        Symbol free = module.findSymbolByName("_free");
        assertNotNull(malloc_default_zone);
        Pointer zone = UnicornPointer.pointer(emulator, malloc_default_zone.call(emulator)[0].intValue());
        assertNotNull(zone);
        Pointer malloc = zone.getPointer(0xc);
        Pointer block = UnicornPointer.pointer(emulator, MachOModule.emulateFunction(emulator, ((UnicornPointer) malloc).peer, zone, 1)[0].intValue());
        assertNotNull(block);
        Pointer sizeFun = zone.getPointer(0x8);
        int size = MachOModule.emulateFunction(emulator, ((UnicornPointer) sizeFun).peer, zone, block)[0].intValue();
        int mSize = malloc_size.call(emulator, block)[0].intValue();
        System.out.println("malloc_num_zones=" + malloc_num_zones.createPointer(emulator).getInt(0) + ", version=" + zone.getInt(0x34) + ", free_definite_size=" + zone.getPointer(0x3c));

        Symbol malloc_zone_malloc = module.findSymbolByName("_malloc_zone_malloc");
        System.err.println("malloc_default_zone=" + malloc_default_zone + ", zone=" + zone + ", malloc=" + malloc +
                ", sizeFun=" + sizeFun + ", block=" + block + ", size=" + size + ", mSize=" + mSize + ", malloc_zone_malloc=0x" + Long.toHexString(malloc_zone_malloc.getAddress()));

        free.call(emulator, block);

        IHookZz hookZz = HookZz.getInstance(emulator);
//        Logger.getLogger("cn.banny.unidbg.ios.ARM32SyscallHandler").setLevel(Level.DEBUG);
        Pointer pointer = malloc_zone_malloc.createPointer(emulator);
        Inspector.inspect(pointer.getByteArray(0, 0x10), "Before replace pointer=" + pointer);
//        emulator.traceCode();
//        emulator.attach().addBreakPoint(null, 0x41178b5a);
        hookZz.replace(malloc_zone_malloc, new ReplaceCallback() {
            @Override
            public HookStatus onCall(Emulator emulator, long originFunction) {
                Unicorn unicorn = emulator.getUnicorn();
                Pointer zone = UnicornPointer.register(emulator, ArmConst.UC_ARM_REG_R0);
                int size = ((Number) unicorn.reg_read(ArmConst.UC_ARM_REG_R1)).intValue();
                System.err.println("_malloc_zone_malloc zone=" + zone + ", size=" + size);
                return HookStatus.RET(unicorn, originFunction);
            }
        });
        Inspector.inspect(pointer.getByteArray(0, 0x10), "After replace");

//        emulator.traceCode();
        hookZz.wrap(module.findSymbolByName("_free"), new WrapCallback<Arm32RegisterContext>() {
            @Override
            public void preCall(Emulator emulator, Arm32RegisterContext ctx, HookEntryInfo info) {
                System.err.println("preCall _free=" + ctx.getR0Pointer());
            }
        });

        Symbol symbol = module.findSymbolByName("_MSGetImageByName");
        assertNotNull(symbol);

//        emulator.attach().addBreakPoint(null, 0x40235d2a);
//        emulator.traceCode();

        MemoryBlock memoryBlock = emulator.getMemory().malloc(0x40, false);
        UnicornPointer memory = memoryBlock.getPointer();
        Symbol _snprintf = module.findSymbolByName("_snprintf", true);
        assertNotNull(_snprintf);

        byte[] before = memory.getByteArray(0, 0x40);
        Inspector.inspect(before, "Before memory=" + memory);
//        emulator.traceCode();
//        emulator.traceWrite(memory.peer, memory.peer + 0x40);
//        emulator.traceWrite();
        String fmt = "Test snprintf=%p\n";
//        emulator.traceRead(0xbffff9b8L, 0xbffff9b8L + fmt.length() + 1);
//        emulator.attach().addBreakPoint(null, 0x401622c2);
        _snprintf.call(emulator, memory, 0x40, fmt, memory);
        byte[] after = memory.getByteArray(0, 0x40);
        Inspector.inspect(after, "After");
        if (Arrays.equals(before, after)) {
            throw new AssertionFailedError();
        }
//        emulator.attach().addBreakPoint(null, 0x40234c1e);
        memoryBlock.free(false);

        long start = System.currentTimeMillis();

//        emulator.traceRead();
//        emulator.attach().addBreakPoint(null, 0x401495dc);
//        emulator.traceCode();

        whale.WInlineHookFunction(module.findSymbolByName("_malloc"), new ReplaceCallback() {
            @Override
            public HookStatus onCall(Emulator emulator, long originFunction) {
                Unicorn unicorn = emulator.getUnicorn();
                int size = ((Number) unicorn.reg_read(ArmConst.UC_ARM_REG_R0)).intValue();
                System.err.println("onCall _malloc size=" + size + ", origin=" + UnicornPointer.pointer(emulator, originFunction));
                return HookStatus.RET(unicorn, originFunction);
            }
        });

        Logger.getLogger("cn.banny.unidbg.AbstractEmulator").setLevel(Level.DEBUG);
//        emulator.traceCode();
        whale.WImportHookFunction("_strcmp", new ReplaceCallback() {
            @Override
            public HookStatus onCall(Emulator emulator, long originFunction) {
                Arm32RegisterContext context = emulator.getRegisterContext();
                Pointer pointer1 = context.getR0Pointer();
                Pointer pointer2 = context.getR1Pointer();
                System.out.println("strcmp str1=" + pointer1.getString(0) + ", str2=" + pointer2.getString(0) + ", originFunction=0x" + Long.toHexString(originFunction));
                return HookStatus.RET(emulator.getUnicorn(), originFunction);
            }
        });

        // emulator.attach().addBreakPoint(module, 0x00b608L);
//        emulator.traceCode();
        Number[] numbers = symbol.call(emulator, "/Library/Frameworks/CydiaSubstrate.framework/CydiaSubstrate");
        long ret = numbers[0].intValue() & 0xffffffffL;
        System.err.println("_MSGetImageByName ret=0x" + Long.toHexString(ret) + ", offset=" + (System.currentTimeMillis() - start) + "ms");

        symbol = module.findSymbolByName("_MSFindSymbol");
        assertNotNull(symbol);
        start = System.currentTimeMillis();
        // emulator.traceCode();
        numbers = symbol.call(emulator, ret, "_MSGetImageByName");
        ret = numbers[0].intValue() & 0xffffffffL;
        System.err.println("_MSFindSymbol ret=0x" + Long.toHexString(ret) + ", offset=" + (System.currentTimeMillis() - start) + "ms");
    }

    public static void main(String[] args) throws Exception {
        SubstrateTest test = new SubstrateTest();
        test.setUp();
        test.testMS();
        test.tearDown();
    }

}
