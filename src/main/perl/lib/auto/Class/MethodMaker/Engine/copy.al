# NOTE: Derived from blib/lib/Class/MethodMaker/Engine.pm.
# Changes made here will be lost when autosplit is run again.
# See AutoSplit.pm.
package Class::MethodMaker::Engine;

#line 1038 "blib/lib/Class/MethodMaker/Engine.pm (autosplit into blib/lib/auto/Class/MethodMaker/Engine/copy.al)"
# ----------------------------------------------------------------------------


sub copy {
  my $class = shift;
  my ($tclass, $name, $options, $global) = @_;

  check_opts([qw/ v1_compat deep /], $options);

  if ( $options->{deep} ) {
    eval 'use Storable;';
    eval 'use Data::Dumper;' if $@;
    die("Couldn't find required Data::Dumper module for deep copying: $@\n",
        "(which is odd, 'cause it's part of the core...\n")
      if $@;
    return +{ '*' => sub {
                my $self = shift; my $class = ref $self;

                if ( Storable->VERSION ) {
                  return Storable::dclone $self;
                } else {
                  my $copy;
                  eval Data::Dumper->Dump([$self],['copy']);
                  return $copy;
                }
              },
            };
  } else {
    return +{ '*' => sub {
                my $self = shift; my $class = ref $self;
                return bless { %$self }, $class;
              },
            };
  }
}

# end of Class::MethodMaker::Engine::copy
1;
