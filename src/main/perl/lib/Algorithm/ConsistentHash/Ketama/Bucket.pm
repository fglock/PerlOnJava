package Algorithm::ConsistentHash::Ketama::Bucket;

use strict;

sub new {
    my ($class, $args) = @_;
    return bless { label => $args->{label}, weight => $args->{weight} }, $class;
}
sub label  { $_[0]{label} }
sub weight { $_[0]{weight} }

1;
