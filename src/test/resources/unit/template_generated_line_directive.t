use strict;
use warnings;
use File::Temp qw(tempfile);
use File::Spec;
use Test::More tests => 9;

my $source = <<'PERL';
#line 1 "template-generated.tt"
sub {
    my $numerator = 420;
    my $denominator = 0;
    return $numerator / $denominator;
}
PERL

my $compiled = eval $source;

ok(defined $compiled, 'generated template sub compiles');

my $result = eval { $compiled->() };
is($result, undef, 'generated template exception returns undef through eval');
is($@,
    "Illegal division by zero at template-generated.tt line 4.\n",
    'eval-generated template exception honors logical source line');

my ($handle, $path) = tempfile(
    'template-generated-XXXX',
    DIR => File::Spec->tmpdir,
    SUFFIX => '.pl',
    UNLINK => 1,
);
print {$handle} $source or die "write generated template: $!";
close $handle or die "close generated template: $!";

my $loaded = do $path;
ok(defined $loaded, 'file-generated template sub loads');

$result = eval { $loaded->() };
is($result, undef, 'file-generated template exception returns undef through eval');
is($@,
    "Illegal division by zero at template-generated.tt line 4.\n",
    'file-generated template exception honors logical source line');

my $nested_source = ("# generated boilerplate\n" x 12) . <<'PERL';
sub {
    eval {
#line 1 "template-nested.tt"
        my $numerator = 420;
        my $denominator = 0;
        return $numerator / $denominator;
    };
    return $@;
}
PERL

my $nested = eval $nested_source;
ok(defined $nested, 'nested generated template sub compiles');
my $nested_error = $nested->();
ok(length($nested_error), 'nested generated template exception is captured');
is($nested_error,
    "Illegal division by zero at template-nested.tt line 3.\n",
    'nested generated template exception honors logical source line');
