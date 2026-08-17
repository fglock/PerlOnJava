use strict;
use warnings;
use Test::More;
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use Digest::SHA qw(sha256_hex);

my $sync = File::Spec->catfile('dev', 'import-perl5', 'sync.pl');
do "./$sync" or die "Could not load $sync: $@ $!";

my $root = tempdir(CLEANUP => 1);
my $unicode = File::Spec->catdir($root, 'perl5', 'lib', 'unicore');
make_path($unicode);

my $generator_relative = File::Spec->catfile('perl5', 'lib', 'unicore', 'mktables');
my $generator = File::Spec->catfile($root, $generator_relative);
open my $generator_fh, '>', $generator or die "Cannot create fake generator: $!";
print {$generator_fh} <<'GENERATOR';
use strict;
use warnings;
use File::Spec;
my ($directory, $target);
while (@ARGV) {
    my $argument = shift;
    $directory = shift if $argument eq '-C';
    $target = shift if $argument eq '-T';
}
die "missing generation arguments\n" unless defined $directory && defined $target;
open my $output, '>', $target or die "Cannot create $target: $!";
print {$output} <<'PREAMBLE';
# generated fixture
my $helper_lexical = 1;
sub Expect { $main::testprop_expects += $helper_lexical }
sub Error { $main::testprop_errors += $helper_lexical }
sub Test_GCB { $main::testprop_breaks += $helper_lexical }
sub Test_SB { $main::testprop_breaks += $helper_lexical }
sub Test_LB { $main::testprop_breaks += $helper_lexical }
sub Test_WB { $main::testprop_breaks += $helper_lexical }
sub Finished { $main::testprop_finished++ }
PREAMBLE
for my $chunk (1 .. 10) {
    print {$output} "if (!\$::TESTCHUNK or \$::TESTCHUNK == $chunk) {\n";
    print {$output} "    \$main::testprop_chunks .= '$chunk,';\n";
    if ($chunk <= 4) {
        for my $index (1 .. 600) {
            my $suffix = " # chunk-$chunk-expect-$index";
            $suffix .= " regression-$index"
                if $chunk == 1 && $index <= 16;
            print {$output} "    Expect();$suffix\n";
        }
        print {$output} "    Error(); # chunk-$chunk-error-$_\n" for 1 .. 300;
    }
    elsif ($chunk == 5) {
        print {$output} "    Test_GCB();\n" for 1 .. 120;
        print {$output} "    Test_SB();\n" for 1 .. 80;
    }
    elsif ($chunk <= 9) {
        print {$output} "    Test_LB();\n" for 1 .. 80;
    }
    else {
        print {$output} "    Test_WB();\n" for 1 .. 80;
    }
    print {$output} "}\n";
}
print {$output} "Finished();\n";
close $output;
GENERATOR
close $generator_fh;

for my $required (qw(version UnicodeData.txt)) {
    my $path = File::Spec->catfile($unicode, $required);
    open my $fh, '>', $path or die "Cannot create $path: $!";
    print {$fh} $required eq 'version' ? "17.0.0\n" : "0041;LATIN CAPITAL LETTER A\n";
    close $fh;
}

my $target = File::Spec->catfile($root, 'perl5_t', 'lib', 'unicore', 'TestProp.pl');
my @family = ($target, map {
    File::Spec->catfile($root, 'perl5_t', 'lib', 'unicore',
                       sprintf('TestProp-%02d.pl', $_))
} 1 .. 10);

sub slurp {
    my ($path) = @_;
    open my $fh, '<', $path or die "Cannot read $path: $!";
    local $/;
    my $contents = <$fh>;
    close $fh;
    return $contents;
}

ok(generate_unicode_testprop($generator_relative, $target, $root),
   'generated import succeeds from pinned inputs');
ok(-s $_, "generated fixture is installed: $_") for @family;
my %first_family = map { $_ => slurp($_) } @family;
my $first = $first_family{$target};
like($first, qr/^# Canonical pinned mktables SHA-256: ([0-9a-f]{64})$/m,
     'dispatcher records its canonical generated-input hash');
my ($recorded_canonical_sha) = $first
    =~ /^# Canonical pinned mktables SHA-256: ([0-9a-f]{64})$/m;
my @reconstructed_sections;
for my $chunk (1 .. 10) {
    like($first, qr/^# TESTCHUNK $chunk: /m,
         "dispatcher records TESTCHUNK $chunk counts");
    my $path = $family[$chunk];
    my $source = $first_family{$path};
    my $marker = "if (!\$::TESTCHUNK or \$::TESTCHUNK == $chunk) {";
    my $start = index($source, $marker);
    cmp_ok($start, '>=', 0, "chunk $chunk contains its canonical marker");
    is(index($source, 'TESTCHUNK == ', $start + length $marker), -1,
       "chunk $chunk contains no later TESTCHUNK section");
    push @reconstructed_sections, substr($source, $start);
}
my ($canonical_preamble) = split /\n# PerlOnJava lossless TESTCHUNK dispatcher\n/,
    $first, 2;
my $reconstructed = $canonical_preamble
    . join('', @reconstructed_sections) . "Finished();\n";
is(sha256_hex($reconstructed), $recorded_canonical_sha,
   'dispatcher and chunks reconstruct the canonical generated hash');
my @expects = $reconstructed =~ /^\s+Expect\(/mg;
my @errors = $reconstructed =~ /^\s+Error\(/mg;
is(scalar @expects, 4 * 600, 'all canonical Expect calls are retained');
is(scalar @errors, 4 * 300, 'all canonical Error calls are retained');
like($reconstructed, qr/# chunk-2-expect-1$/m,
     'first ordered Expect call is retained');
like($reconstructed, qr/# chunk-2-expect-600$/m,
     'last ordered Expect call is retained');
like($reconstructed, qr/# chunk-2-error-1$/m,
     'first ordered Error call is retained');
like($reconstructed, qr/# chunk-2-error-300$/m,
     'last ordered Error call is retained');

{
    local $::TESTCHUNK = 2;
    local $::testprop_chunks = '';
    local $::testprop_expects = 0;
    local $::testprop_errors = 0;
    local $::testprop_breaks = 0;
    local $::testprop_finished = 0;
    my $loaded = do $target;
    ok(defined $loaded, 'dispatcher executes one selected generated chunk')
        or diag "dispatcher error: $@ $!";
    is($::testprop_chunks, '2,', 'dispatcher loads exactly the selected chunk');
    is($::testprop_expects, 600,
       'selected chunk eval sees canonical lexical helper scope');
    is($::testprop_errors, 300, 'selected chunk retains every Error call');
    is($::testprop_finished, 1, 'selected dispatch calls canonical Finished once');
}
{
    local $::TESTCHUNK = 0;
    local $::testprop_chunks = '';
    local $::testprop_expects = 0;
    local $::testprop_errors = 0;
    local $::testprop_breaks = 0;
    local $::testprop_finished = 0;
    my $loaded = do $target;
    ok(defined $loaded, 'dispatcher executes all chunks when none is selected')
        or diag "dispatcher error: $@ $!";
    is($::testprop_chunks, '1,2,3,4,5,6,7,8,9,10,',
       'unselected dispatch preserves canonical chunk order');
    is($::testprop_expects, 4 * 600, 'all-chunk dispatch retains all Expect calls');
    is($::testprop_errors, 4 * 300, 'all-chunk dispatch retains all Error calls');
    is($::testprop_breaks, 120 + 80 + 4 * 80 + 80,
       'all-chunk dispatch retains all boundary calls');
    is($::testprop_finished, 1, 'all-chunk dispatch calls canonical Finished once');
}

open my $damage_fh, '>', $target or die "Cannot alter $target: $!";
print {$damage_fh} "damaged\n";
close $damage_fh;
open my $chunk_damage_fh, '>', $family[7]
    or die "Cannot alter $family[7]: $!";
print {$chunk_damage_fh} "damaged chunk\n";
close $chunk_damage_fh;
ok(generate_unicode_testprop($generator_relative, $target, $root),
   'repeated generation succeeds');
my %second_family = map { $_ => slurp($_) } @family;
is_deeply(\%second_family, \%first_family,
          'repeated generation installs a byte-identical dispatcher and chunks');

unlink File::Spec->catfile($unicode, 'version')
    or die "Cannot remove fake version prerequisite: $!";
my $warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= join '', @_ };
    ok(!generate_unicode_testprop($generator_relative, $target, $root),
       'missing generation prerequisite fails');
}
like($warning, qr/missing pinned generation prerequisite.*version/s,
     'missing prerequisite diagnostic names the absent input');

my $bad_order = join '',
    "if (!\$::TESTCHUNK or \$::TESTCHUNK == 2) {\n}\n",
    "if (!\$::TESTCHUNK or \$::TESTCHUNK == 1) {\n}\n",
    (map { "if (!\$::TESTCHUNK or \$::TESTCHUNK == $_) {\n}\n" } 3 .. 10),
    "Finished();\n";
my $order_error = '';
eval { split_unicode_testprop($bad_order); 1 } or $order_error = $@;
like($order_error, qr/unexpected TESTCHUNK order: 2,1,3,4,5,6,7,8,9,10/,
     'splitting rejects dropped or reordered canonical sections');

done_testing;
