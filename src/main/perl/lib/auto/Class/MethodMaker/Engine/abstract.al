# NOTE: Derived from blib/lib/Class/MethodMaker/Engine.pm.
# Changes made here will be lost when autosplit is run again.
# See AutoSplit.pm.
package Class::MethodMaker::Engine;

#line 990 "blib/lib/Class/MethodMaker/Engine.pm (autosplit into blib/lib/auto/Class/MethodMaker/Engine/abstract.al)"
# ----------------------------------------------------------------------------


sub abstract {
  my $class = shift;
  my ($tclass, $name, $options, $global) = @_;

  my %known_options = map {; $_ => 1 } qw( v1_compat
                                         );
  if ( my @bad_opt = grep ! exists $known_options{$_}, keys %$options ) {
    my $prefix = 'Option' . (@bad_opt > 1 ? 's' : '');
    croak("$prefix not recognized for attribute type abstract: ",
          join(', ', @bad_opt), "\n");
  }

  return +{ '*' => sub {
              my ($self) = @_;
              my $cclass = ref $self;
              die <<"END";
Cannot invoke abstract method '${tclass}::${name}', called from '$cclass'.
END
            },
    };
}

# end of Class::MethodMaker::Engine::abstract
1;
