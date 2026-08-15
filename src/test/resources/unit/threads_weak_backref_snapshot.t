use strict;
use warnings;
use threads;
use Scalar::Util qw(isweak refaddr weaken);

print "1..3\n";

{
    package ThreadWeakSchema;
    sub DESTROY { }
}

our $weak_global;
my $schema = bless {}, 'ThreadWeakSchema';
my $source = { schema => $schema };
weaken($source->{schema});
$schema->{source} = $source;
$weak_global = $schema;
weaken($weak_global);

my $result = threads->create(sub {
    return join ':',
        defined($weak_global) ? 1 : 0,
        defined($schema->{source}{schema}) ? 1 : 0,
        (defined($weak_global) && refaddr($weak_global) == refaddr($schema)) ? 1 : 0;
})->join();

my ($global_live, $backref_live, $same_clone) = split /:/, $result;
print $global_live ? "ok 1 - weak global survives a strong entry capture\n"
                   : "not ok 1 - weak global survives a strong entry capture\n";
print $backref_live && isweak($schema->{source}{schema})
    ? "ok 2 - cloned weak back-reference retains its captured owner\n"
    : "not ok 2 - cloned weak back-reference retains its captured owner\n";
print $same_clone ? "ok 3 - weak global and entry capture share one child clone\n"
                  : "not ok 3 - weak global and entry capture share one child clone\n";
