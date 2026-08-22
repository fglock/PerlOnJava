#!/usr/bin/env perl

use strict;
use warnings;

use Cwd qw(abs_path);
use File::Find qw(find);
use File::Spec;
use FindBin qw($Bin);

die "Usage: $0 <install-directory>\n" unless @ARGV == 1;
my $distribution = abs_path($ARGV[0]);
die "Installed distribution is missing: $ARGV[0]\n"
    unless defined $distribution && -d $distribution;

my $root = abs_path(File::Spec->catdir($Bin, '..', '..'))
    or die "Cannot resolve repository root from $Bin\n";
my $lib = File::Spec->catdir($distribution, 'lib');
die "Installed distribution has no lib directory\n" unless -d $lib;

opendir my $lib_fh, $lib or die "Cannot open $lib: $!\n";
my @jars = sort map { File::Spec->catfile($lib, $_) }
    grep { /\.jar\z/i && -f File::Spec->catfile($lib, $_) } readdir $lib_fh;
closedir $lib_fh or die "Cannot close $lib: $!\n";
die "Installed distribution must contain exactly one runtime JAR (found "
    . scalar(@jars) . ")\n" unless @jars == 1;
my $jar = $jars[0];

my %entries;
open my $jar_fh, '-|', 'jar', 'tf', $jar
    or die "Cannot list $jar: $!\n";
while (my $entry = <$jar_fh>) {
    chomp $entry;
    $entries{$entry}++;
}
close $jar_fh or die "Cannot list $jar: jar exited with status $?\n";

for my $entry (sort keys %entries) {
    next unless $entry =~ /\.class\z/;
    die "Runtime JAR contains duplicate class entry $entry\n"
        unless $entries{$entry} == 1;
}
die "Runtime JAR contains unrelocated Joni classes\n"
    if grep { m{^org/joni/.*\.class\z} } keys %entries;
die "Runtime JAR contains unrelocated JCodings classes\n"
    if grep { m{^org/jcodings/.*\.class\z} } keys %entries;
die "Runtime JAR does not contain relocated Joni classes\n"
    unless grep { m{^org/perlonjava/internal/joni/.*\.class\z} } keys %entries;
die "Runtime JAR does not contain relocated JCodings classes\n"
    unless grep { m{^org/perlonjava/internal/jcodings/.*\.class\z} } keys %entries;

my @loose_unrelocated;
find({
    no_chdir => 1,
    wanted => sub {
        return unless -f $_;
        my $relative = File::Spec->abs2rel($_, $distribution);
        $relative =~ s{\\}{/}g;
        push @loose_unrelocated, $relative
            if $relative =~ m{(?:^|/)org/(?:joni|jcodings)/.*\.class\z};
    },
}, $distribution);
die "Installed distribution contains unrelocated loose class $loose_unrelocated[0]\n"
    if @loose_unrelocated;

my %notices = (
    'joni-LICENSE.txt' => File::Spec->catfile($root, 'third_party', 'joni', 'LICENSE'),
    'joni-PERLONJAVA-NOTICE.md' => File::Spec->catfile(
        $root, 'third_party', 'joni', 'PERLONJAVA-NOTICE.md'),
    'jcodings-LICENSE.txt' => File::Spec->catfile(
        $root, 'third_party', 'licenses', 'jcodings-LICENSE.txt'),
);
my $license_directory = File::Spec->catdir($distribution, 'share', 'licenses');
die "Installed distribution has no stable share/licenses directory\n"
    unless -d $license_directory;
for my $name (sort keys %notices) {
    my $installed = File::Spec->catfile($license_directory, $name);
    die "Installed distribution is missing $name\n" unless -f $installed;
    die "Installed distribution notice bytes differ for $name\n"
        unless read_raw($installed) eq read_raw($notices{$name});
}

my $jar_name = (File::Spec->splitpath($jar))[2];
verify_unix_launcher(
    File::Spec->catfile($distribution, 'bin', 'perlonjava'), $jar_name);
verify_windows_launcher(
    File::Spec->catfile($distribution, 'bin', 'perlonjava.bat'), $jar_name);

print "Joni distribution relocation verification passed: $jar\n";

sub read_raw {
    my ($file) = @_;
    open my $fh, '<:raw', $file or die "Cannot read $file: $!\n";
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $file: $!\n";
    return $contents;
}

sub verify_unix_launcher {
    my ($file, $jar_name) = @_;
    die "Installed distribution is missing launcher perlonjava\n" unless -f $file;
    my @lines = split /\n/, read_raw($file), -1;
    s/\r\z// for @lines;

    my @assignments;
    my @unsupported;
    for my $line (@lines) {
        next unless $line =~ /^\h*CLASSPATH\h*=/;
        if ($line =~ /^\h*CLASSPATH\h*=\h*(?:"([^"]*)"|'([^']*)'|([^\h#]+))\h*(?:#.*)?\z/) {
            push @assignments, first_defined($1, $2, $3);
            next;
        }
        next if $line eq '    CLASSPATH=$( cygpath --path --mixed "$CLASSPATH" )';
        push @unsupported, $line;
    }
    die "Installed Unix launcher has unsupported CLASSPATH assignment\n"
        if @unsupported;
    die "Installed Unix launcher must contain exactly one static CLASSPATH assignment (found "
        . scalar(@assignments) . ")\n" unless @assignments == 1;
    my $expected = '$APP_HOME/lib/' . $jar_name;
    die "Installed Unix launcher CLASSPATH must select only $jar_name\n"
        unless $assignments[0] eq $expected;

    my @argv_blocks;
    for my $index (0 .. $#lines - 3) {
        push @argv_blocks, $index
            if $lines[$index] =~ /^\h*set\h+--\h*\\\h*\z/
                && $lines[$index + 1]
                    =~ /^\h*-classpath\h+"\$CLASSPATH"\h*\\\h*\z/
                && $lines[$index + 2]
                    =~ /^\h*org\.perlonjava\.app\.cli\.Main\h*\\\h*\z/
                && $lines[$index + 3] =~ /^\h*"\$@"\h*\z/;
    }
    die "Installed Unix launcher must contain exactly one connected CLASSPATH argv block (found "
        . scalar(@argv_blocks) . ")\n" unless @argv_blocks == 1;
    my $block_start = $argv_blocks[0];
    my $block_end = $block_start + 3;

    my @classpath_commands = grep {
        unix_code_line($lines[$_])
            && $lines[$_] =~ /(?:^|\h)-(?:classpath|cp)(?:\h|\z)/
    } 0 .. $#lines;
    die "Installed Unix launcher has an extra or disconnected classpath command\n"
        unless @classpath_commands == 1
            && $classpath_commands[0] == $block_start + 1;
    my @main_commands = grep {
        unix_code_line($lines[$_])
            && $lines[$_] =~ /(?:^|\h)org\.perlonjava\.app\.cli\.Main(?:\h|\\|\z)/
    } 0 .. $#lines;
    die "Installed Unix launcher has an extra or disconnected Java main command\n"
        unless @main_commands == 1 && $main_commands[0] == $block_start + 2;

    my @exec_commands = grep {
        unix_code_line($lines[$_])
            && $lines[$_] =~ /^\h*exec\h+"\$JAVACMD"\h+"\$@"\h*\z/
    } 0 .. $#lines;
    die "Installed Unix launcher must contain exactly one Java exec command (found "
        . scalar(@exec_commands) . ")\n" unless @exec_commands == 1;
    my $exec_index = $exec_commands[0];
    die "Installed Unix launcher Java exec does not consume the checked argv block\n"
        unless $exec_index > $block_end;

    my %allowed_eval = unix_preserving_eval_lines(\@lines, $block_end, $exec_index);
    for my $index ($block_end + 1 .. $#lines) {
        next unless unix_code_line($lines[$index]);
        next if $index == $exec_index || $allowed_eval{$index};
        die "Installed Unix launcher resets argv after the checked classpath block\n"
            if $lines[$index] =~ /\bset\h+--(?:\h|\z)/;
        die "Installed Unix launcher references CLASSPATH after the checked argv block\n"
            if $lines[$index] =~ /\bCLASSPATH\b/;
        die "Installed Unix launcher has an extra Java command after the checked argv block\n"
            if $lines[$index] =~ /\$JAVACMD\b/
                || $lines[$index] =~ /^\h*(?:[^\h]*[\\\/])?java(?:\.exe)?(?:\h|\z)/i;
    }
}

sub verify_windows_launcher {
    my ($file, $jar_name) = @_;
    die "Installed distribution is missing launcher perlonjava.bat\n" unless -f $file;
    my @lines = split /\r?\n/, read_raw($file), -1;

    my @assignments;
    for my $line (@lines) {
        next unless $line =~ /^\h*set\h+"?CLASSPATH=/i;
        if ($line =~ /^\h*set\h+(?:"CLASSPATH=([^"]*)"|CLASSPATH=([^\r\n]*?))\h*\z/i) {
            push @assignments, first_defined($1, $2);
            next;
        }
        die "Installed Windows launcher has unsupported CLASSPATH assignment\n";
    }
    die "Installed Windows launcher must contain exactly one CLASSPATH assignment (found "
        . scalar(@assignments) . ")\n" unless @assignments == 1;
    my $expected = '%APP_HOME%\\lib\\' . $jar_name;
    die "Installed Windows launcher CLASSPATH must select only $jar_name\n"
        unless $assignments[0] eq $expected;

    my @commands = grep {
        windows_code_line($lines[$_]) && $lines[$_] =~ /^\h*endlocal\h+&\h+
            "%JAVA_EXE%"
            (?:\h+%(?:DEFAULT_JVM_OPTS|JAVA_OPTS|PERLONJAVA_OPTS)%)*
            \h+-classpath\h+"%CLASSPATH%"\h+
            org\.perlonjava\.app\.cli\.Main\h+%\*
            (?:\h+&\h+call\h+:exitWithErrorLevel)?\h*\z/ix
    } 0 .. $#lines;
    die "Installed Windows launcher must contain exactly one effective CLASSPATH command (found "
        . scalar(@commands) . ")\n" unless @commands == 1;
    my $command_index = $commands[0];

    my @classpath_commands = grep {
        windows_code_line($lines[$_])
            && $lines[$_] =~ /(?:^|\h)-(?:classpath|cp)(?:\h|\z)/i
    } 0 .. $#lines;
    die "Installed Windows launcher has an extra or disconnected classpath command\n"
        unless @classpath_commands == 1 && $classpath_commands[0] == $command_index;
    my @main_commands = grep {
        windows_code_line($lines[$_])
            && $lines[$_] =~ /(?:^|\h)org\.perlonjava\.app\.cli\.Main(?:\h|\z)/i
    } 0 .. $#lines;
    die "Installed Windows launcher has an extra or disconnected Java main command\n"
        unless @main_commands == 1 && $main_commands[0] == $command_index;

    for my $index (0 .. $#lines) {
        next unless windows_code_line($lines[$index]);
        next if $index == $command_index;
        next if $lines[$index] =~ /^\h*%JAVA_EXE%\h+-version\h+>NUL\h+2>&1\h*\z/i;
        next if $lines[$index]
            =~ /^\h*if\h+exist\h+"%JAVA_EXE%"\h+goto\h+execute\h*\z/i;
        die "Installed Windows launcher has an extra Java invocation\n"
            if $lines[$index] =~ /%JAVA_EXE%/i
                || $lines[$index]
                    =~ /^\h*(?:(?:call|start)\h+)?"?(?:[^"\h]*[\\\/])?
                        java(?:\.exe)?"?(?:\h|\z)/ix;
        die "Installed Windows launcher references CLASSPATH outside the effective command\n"
            if $lines[$index] =~ /%CLASSPATH%/i;
    }
}

sub unix_preserving_eval_lines {
    my ($lines, $block_end, $exec_index) = @_;
    my @starts = grep {
        unix_code_line($lines->[$_])
            && $lines->[$_] =~ /^\h*eval\h+"set\h+--\h+\$\(\h*\z/
    } $block_end + 1 .. $exec_index - 1;
    return () unless @starts;
    die "Installed Unix launcher has multiple post-classpath eval argv resets\n"
        unless @starts == 1;
    my $start = $starts[0];
    my @expected = split /\n/, <<'PRESERVING_EVAL';
eval "set -- $(
        printf '%s\n' "$DEFAULT_JVM_OPTS $JAVA_OPTS $PERLONJAVA_OPTS" |
        xargs -n1 |
        sed ' s~[^-[:alnum:]+,./:=@_]~\\&~g; ' |
        tr '\n' ' '
    )" '"$@"'
PRESERVING_EVAL
    die "Installed Unix launcher has a non-preserving post-classpath eval argv reset\n"
        if $start + $#expected >= $exec_index;
    for my $offset (0 .. $#expected) {
        die "Installed Unix launcher has a non-preserving post-classpath eval argv reset\n"
            unless $lines->[$start + $offset] eq $expected[$offset];
    }
    return map { ($start + $_) => 1 } 0 .. $#expected;
}

sub unix_code_line {
    my ($line) = @_;
    return defined($line) && $line !~ /^\h*(?:#|\z)/;
}

sub windows_code_line {
    my ($line) = @_;
    return defined($line)
        && $line !~ /^\h*(?:\z|\@?rem(?:\h|\z)|::)/i;
}

sub first_defined {
    for my $value (@_) {
        return $value if defined $value;
    }
    return undef;
}
