use strict;
use warnings;
use Test::More tests => 10;
use Config;
use File::Temp qw(tempfile);
use Symbol qw(gensym);

{
    my ($fh, $path) = tempfile(UNLINK => 1);
    seek $fh, 0, 0 or die "seek $path failed: $!";
    local $/;
    my $slurped = <$fh>;
    ok(defined $slurped, 'slurping a fresh empty handle after seek returns defined');
    is($slurped, '', 'slurping a fresh empty handle after seek returns empty string');
}

{
    my $out = `$^X -V:osname`;
    like($out, qr/\Aosname='\Q$^O\E';\s*\z/, 'perl -V:osname reports the Perl OS name');
    is($Config{osname}, $^O, 'Config osname matches $^O');
    is($Config{_exe}, $^O eq 'MSWin32' ? Config::_perl_launcher_suffix(1, $^X) : '',
       'Config _exe follows the PerlOnJava launcher suffix');
    is(Config::_perl_launcher_suffix(1, 'C:\\PerlOnJava\\jperl.bat'), '.bat',
       'Windows jperl.bat launcher suffix is preserved');
    is(Config::_perl_launcher_suffix(1, 'C:\\PerlOnJava\\jperl.cmd'), '.cmd',
       'Windows jperl.cmd launcher suffix is preserved');
    is(Config::_perl_launcher_suffix(1, 'C:\\PerlOnJava\\jperl'), '.bat',
       'Windows extensionless jperl falls back to batch launcher suffix');
}

{
    my $save_out = gensym();
    my $save_err = gensym();
    open $save_out, '>&STDOUT' or die "dup STDOUT failed: $!";
    open $save_err, '>&STDERR' or die "dup STDERR failed: $!";

    open STDOUT, '>&' . fileno($save_out) or die "restore-style dup STDOUT failed: $!";
    open STDERR, '>&' . fileno($save_err) or die "restore-style dup STDERR failed: $!";
    close STDOUT or die "close STDOUT failed: $!";
    close STDERR or die "close STDERR failed: $!";

    my ($capture, $path) = tempfile(UNLINK => 1);
    my $capture_fd = fileno($capture);
    my $opened = open STDOUT, '>&' . $capture_fd;
    my $open_error = "$!";
    print STDOUT "captured through fd $capture_fd\n" if $opened;

    open STDOUT, '>&' . fileno($save_out) or die "restore STDOUT failed: $!";
    open STDERR, '>&' . fileno($save_err) or die "restore STDERR failed: $!";

    ok($opened, "numeric dup can target a temp file that reused fd $capture_fd")
        or diag "open failed: $open_error";

    seek $capture, 0, 0 or die "seek $path failed: $!";
    local $/;
    my $captured = <$capture>;
    is($captured, "captured through fd $capture_fd\n", 'numeric dup wrote to the reused fd target');
}
