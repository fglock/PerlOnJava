package TestYAMLBase;

sub new {
    my $self = bless {}, shift;
    while (my ($k, $v) = splice @_, 0, 2) {
        $self->{$k} = $v;
    }
    return $self;
}

1;
