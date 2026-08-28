# NOTE: Derived from blib/lib/Class/MethodMaker/Engine.pm.
# Changes made here will be lost when autosplit is run again.
# See AutoSplit.pm.
package Class::MethodMaker::Engine;

#line 908 "blib/lib/Class/MethodMaker/Engine.pm (autosplit into blib/lib/auto/Class/MethodMaker/Engine/new.al)"
sub new {
  my $cmm_class  = shift;
  my ($target_class, $name, $options, $global) = @_;

  check_opts([qw/ init hash direct-init v1_compat singleton /], $options);

  my $init_meth = $options->{init};
  $init_meth = 'init'
    if defined $init_meth and $init_meth eq '1';

  my $new;
  my $singleton;
  if ( $options->{hash} ) {
    $new = sub {
      my $self =
        $options->{singleton}                            ?
          ($singleton || ($singleton = bless {}, $_[0])) :
          (ref ($_[0]) ? $_[0] : bless {}, $_[0])        ;
      my $class = ref $self || $self;

      my %args;
      if ( @_ == 2 and ref($_[1]) eq 'HASH' ) {
        %args = %{ $_[1] };
      } elsif ( @_ % 2 ) {
        %args = @_[1..$#_];
      } else {
        die "Odd number of arguments for $name\n";
      }

      foreach (keys %args) {
        my $assign = $cmm_class->_class_comp_assign($class, $_);
        if ( defined $assign and my $setter = $class->can($assign) ) {
          $setter->($self, $args{$_});
        } else {
          $self->$_($args{$_});
        }
      }
      $self->$init_meth(@_[1..$#_])
        if $init_meth;

      $self;
    };
  } elsif ( $init_meth ) {
    $new = sub {
      my $class = ref $_[0] || $_[0];
      my $self =
        $options->{singleton}                              ?
          ($singleton || ($singleton = bless +{}, $class)) :
          bless(+{}, $class)                               ;
      $self->$init_meth(@_[1..$#_]);
      $self;
    };
  } elsif ( $options->{'direct-init'} ) {
    # This is here purely for v1 compatibility.  It can be trivially
    # implemented with -init, so is not explicitly supported for V2.
    $new = sub {
      my $class = ref $_[0] || $_[0];
      bless +{@_[1..$#_]}, $class;
    };
  } else {
    $new = sub {
      my $class = ref $_[0] || $_[0];
      $options->{singleton}                              ?
        ($singleton || ($singleton = bless +{}, $class)) :
        bless(+{}, $class)                               ;
    };
  }

  return +{ '*' => $new,
          };
}

# end of Class::MethodMaker::Engine::new
1;
