use strict;
use warnings;
use threads;
use Scalar::Util qw(weaken);
use B ();

print "1..2\n";

{
    package Local::Schema;

    sub source { $_[0]{sources}{Artist} }

    sub DESTROY {
        my $self = shift;
        my $sources = $self->{sources};
        for my $name (keys %$sources) {
            next unless ref($sources->{$name});
            if (B::svref_2object($sources->{$name})->REFCNT > 1) {
                $sources->{$name}{schema} = $self;
                Scalar::Util::weaken($sources->{$name});
                last;
            }
        }
    }
}

my $schema = bless { sources => {} }, 'Local::Schema';
my $source = { schema => $schema };
weaken($source->{schema});
$schema->{sources}{Artist} = $source;

my $thread = threads->create(sub {
    my $result_source = $schema->source;
    undef $schema;
    return defined($result_source->{schema}) ? 1 : 0;
});

print(($thread->join ? "ok" : "not ok"),
    " 1 - method-return lexical keeps its referent alive during owner DESTROY\n");
print(defined($source->{schema})
    ? "ok 2 - parent graph remains intact\n"
    : "not ok 2 - parent graph remains intact\n");
